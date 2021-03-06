(ns jinteki.decks
  (:require [clojure.string :refer [split split-lines join escape] :as s]
            [jinteki.utils :refer [faction-label INFINITY]]
            [jinteki.cards :refer [all-cards] :as cards]
            #?@(:clj [[clj-time.core :as t] [clj-time.format :as f]])))

(defn card-count [cards]
  (reduce #(+ %1 (:qty %2)) 0 cards))


;;; Helpers for Alliance cards
(defn- is-alliance?
  "Checks if the card is an alliance card"
  [card]
  ;; All alliance cards
  (let [ally-cards #{"10013" "10018" "10019" "10029" "10038" "10067" "10068" "10071" "10072" "10076" "10094" "10109"}
        card-code (:code (:card card))]
    (ally-cards card-code)))

(defn- default-alliance-is-free?
  "Default check if an alliance card is free - 6 non-alliance cards of same faction."
  [cards line]
  (<= 6 (card-count (filter #(and (= (get-in line [:card :faction])
                                     (get-in % [:card :faction]))
                                  (not (is-alliance? %)))
                            cards))))

(defn- alliance-is-free?
  "Checks if an alliance card is free"
  [cards {:keys [card] :as line}]
  (case (:code card)
    ("10013"                                               ; Heritage Committee
     "10029"                                               ; Product Recall
     "10067"                                               ; Jeeves Model Bioroids
     "10068"                                               ; Raman Rai
     "10071"                                               ; Salem's Hospitality
     "10072"                                               ; Executive Search Firm
     "10094"                                               ; Consulting Visit
     "10109")                                              ; Ibrahim Salem
    (default-alliance-is-free? cards line)
    "10018"                                                 ; Mumba Temple
    (>= 15 (card-count (filter #(= "ICE" (:type (:card %))) cards)))
    "10019"                                                 ; Museum of History
    (<= 50 (card-count cards))
    "10038"                                                 ; PAD Factory
    (= 3 (card-count (filter #(= "PAD Campaign" (:title (:card %))) cards)))
    "10076"                                                 ; Mumbad Virtual Tour
    (<= 7 (card-count (filter #(= "Asset" (:type (:card %))) cards)))
    ;; Not an alliance card
    false))

;; Basic deck rules
(defn min-deck-size
  "Contains implementation-specific decksize adjustments, if they need to be different from printed ones."
  [identity]
  (:minimumdecksize identity))

(defn min-agenda-points [deck]
  (let [size (max (card-count (:cards deck)) (min-deck-size (:identity deck)))]
    (+ 2 (* 2 (quot size 5)))))

(defn is-draft-id?
  "Check if the specified id is a draft identity"
  [identity]
  (= "Draft" (:setname identity)))

(defn id-inf-limit
  "Returns influence limit of an identity or INFINITY in case of draft IDs."
  [identity]
  (if (is-draft-id? identity) INFINITY (:influencelimit identity)))

(defn legal-num-copies?
  "Returns true if there is a legal number of copies of a particular card."
  [identity {:keys [qty card]}]
  (or (is-draft-id? identity)
      (<= qty (or (:limited card) 3))))

(defn is-prof-prog?
  "Check if ID is The Professor and card is a Program"
  [deck card]
  (and (= "03029" (get-in deck [:identity :code]))
       (= "Program" (:type card))))

(defn- before-today? [date]
  #?(:clj  (let [parsed-date (if (string? date)
                               (f/parse (f/formatters :date) date)
                               date)]
             (if (nil? parsed-date)
               false
               (t/before? parsed-date (t/now))))
     :cljs (< date (.toJSON (js/Date.)))))

(defn released?
  "Returns false if the card comes from a spoiled set or is out of competitive rotation."
  [sets card]
  (let [card-set (:setname card)
        rotated (:rotated card)
        date (some #(when (= (:name %) card-set) (:available %)) sets)]
    (and (not rotated)
         (not= date "")
         (not (nil? date))
         (before-today? date))))

;; Influence
;; Note: line is a map with a :card and a :qty
(defn line-base-cost
  "Returns the basic influence cost of a deck-line"
  [identity-faction {:keys [card qty]}]
  (let [card-faction (:faction card)]
    (if (= identity-faction card-faction)
      0
      (* qty (:factioncost card 0)))))

(defn line-influence-cost
  "Returns the influence cost of the specified card"
  [deck line]
  (let [identity-faction (get-in deck [:identity :faction])
        base-cost (line-base-cost identity-faction line)]
    ;; Do not care about discounts if the base cost is 0 (in faction or free neutral)
    (if (zero? base-cost)
      0
      (cond
        ;; The Professor: Keeper of Knowledge - discount influence cost of first copy of each program
        (is-prof-prog? deck (:card line))
        (- base-cost (get-in line [:card :factioncost]))
        ;; Check if the card is Alliance and fulfills its requirement
        (alliance-is-free? (:cards deck) line)
        0
        :else
        base-cost))))

(defn influence-map
  "Returns a map of faction keywords to influence values from the faction's cards."
  [deck]
  (letfn [(infhelper [infmap line]
            (let [inf-cost (line-influence-cost deck line)
                  faction (keyword (faction-label (:card line)))]
              (update infmap faction #(+ (or % 0) inf-cost))))]
    (reduce infhelper {} (:cards deck))))

;; Deck attribute calculations
(defn agenda-points [{:keys [cards] :as deck}]
  (reduce #(if-let [point (get-in %2 [:card :agendapoints])]
             (+ (* point (:qty %2)) %1) %1) 0 cards))

(defn influence-count
  "Returns sum of influence count used by a deck."
  [deck]
  (apply + (vals (influence-map deck))))

;; Rotation and MWL
(defn title->keyword
  [card]
  (-> card :normalizedtitle keyword))

(defn banned-cards
  "Returns a list of card codes that are on the MWL banned list"
  []
  (->> (:cards @cards/mwl)
       (filter (fn [[k v]] (contains? v :deck-limit)))
       (map key)
       set))

(defn banned?
  "Returns true if the card is on the MWL banned list"
  [card]
  (contains? (banned-cards) (title->keyword card)))

(defn contains-banned-cards
  "Returns true if any of the cards are in the MWL banned list"
  [deck]
  (or (some #(banned? (:card %)) (:cards deck))
      (banned? (:identity deck))))

(defn restricted-cards
  "Returns a list of card codes that are on the MWL restricted list"
  []
  (->> (:cards @cards/mwl)
       (filter (fn [[k v]] (contains? v :is-restricted)))
       (map key)
       set))

(defn restricted?
  "Returns true if the card is on the MWL restricted list"
  [card]
  (contains? (restricted-cards) (title->keyword card)))

(defn restricted-card-count
  "Returns the number of *types* of restricted cards"
  [deck]
  (->> (conj (:cards deck) {:card (:identity deck)})
       (filter (fn [c] (restricted? (:card c))))
       (map (fn [c] (:title (:card c))))
       (distinct)
       (count)))


;; alternative formats validation
(defn group-cards-from-restricted-sets
  "Return map (big boxes and datapacks) of used sets that are restricted by given format"
  [sets allowed-sets deck]
  (let [restricted-cards (remove (fn [card] (some #(= (:setname (:card card)) %) allowed-sets)) (:cards deck))
        restricted-sets (group-by (fn [card] (:setname (:card card))) restricted-cards)
        sorted-restricted-sets (reverse (sort-by #(count (second %)) restricted-sets))
        [restricted-bigboxes restricted-datapacks] (split-with (fn [[setname _]]
                                                                 (some #(when (= (:name %) setname)
                                                                          (:bigbox %)) sets))
                                                               sorted-restricted-sets)]
    {:bigboxes restricted-bigboxes
     :datapacks restricted-datapacks}))

(defn cards-over-one-core
  "Returns cards in deck that require more than single box."
  [deck]
  (let [one-box-num-copies? (fn [{:keys [qty card]}] (<= qty (or (:packquantity card) 3)))]
    (remove one-box-num-copies? (:cards deck))))

(defn get-newest-cycles
  "Returns n cycles of data packs from newest backwards"
  [sets n]
  (let [cycles (group-by :cycle (remove :bigbox sets))
        parse-date #?(:clj  #(f/parse (f/formatters :date) %)
                      :cljs identity)
        cycle-release-date (reduce-kv (fn [result cycle sets-in-cycle]
                                        (assoc result
                                          cycle
                                          (first (sort (mapv #(parse-date (:available %)) sets-in-cycle)))))
                                      {} cycles)
        valid-cycles (map first (take-last n (sort-by last (filter (fn [[cycle date]] (before-today? date)) cycle-release-date))))]
    valid-cycles))

(defn sets-in-newest-cycles
  "Returns sets in the n cycles of released datapacks"
  [sets n]
  (map :name (filter (fn [set] (some #(= (:cycle set) %) (get-newest-cycles sets n))) sets)))

(defn modded-legal
  "Returns true if deck is valid under Modded rules. https://forum.stimhack.com/t/modded-format-online-league-starts-april-14/9791"
  [sets deck]
  (let [revised-core "Revised Core Set"
        valid-sets (concat [revised-core] (sets-in-newest-cycles sets 1))
        deck-with-id (assoc deck :cards (cons {:card (:identity deck) } (:cards deck))) ;identity should also be from valid sets
        restricted-sets (group-cards-from-restricted-sets sets valid-sets deck-with-id)
        restricted-bigboxes (remove #(= revised-core %) (:bigboxes restricted-sets))
        restricted-datapacks (:datapacks restricted-sets)
        example-card (fn [cardlist] (get-in (first cardlist) [:card :title]))
        reasons {:bigbox (when (not= (count restricted-bigboxes) 0)
                           (str "Only Revised Core Set permitted - check: " (example-card (second (first restricted-bigboxes)))))
                 :datapack (when (not= (count restricted-datapacks) 0)
                             (str "Only the most recent cycles permitted - check: " (example-card (second (first restricted-datapacks)))))}]
    {:legal (not-any? val reasons)
     :reason (join "\n" (filter identity (vals reasons)))
     :description "Modded format compliant"}))

(defn cache-refresh-legal
  "Returns true if deck is valid under Cache Refresh rules. http://www.cache-refresh.info/"
  ([sets deck] (cache-refresh-legal sets deck (concat ["Terminal Directive"] (sets-in-newest-cycles sets 2)) "Cache Refresh compliant"))
  ([sets deck valid-sets description]
   (let [over-one-core (cards-over-one-core deck)
         valid-sets (concat ["Revised Core Set"] valid-sets)
         deck-with-id (assoc deck :cards (cons {:card (:identity deck)} (:cards deck))) ; identity should also be from valid sets
         restricted-sets (group-cards-from-restricted-sets sets valid-sets deck-with-id)
         restricted-bigboxes (rest (:bigboxes restricted-sets)) ; one big box is fine
         restricted-datapacks (:datapacks restricted-sets)
         example-card (fn [cardlist] (get-in (first cardlist) [:card :title]))
         reasons {:onecore (when (not= (count over-one-core) 0)
                             (str "Only one Revised Core Set permitted - check: " (example-card over-one-core)))
                  :bigbox (when (not= (count restricted-bigboxes) 0)
                            (str "Only one Deluxe Expansion permitted - check: " (example-card (second (first restricted-bigboxes)))))
                  :datapack (when (not= (count restricted-datapacks) 0)
                              (str "Only latest 2 cycles are permitted - check: " (example-card (second (first restricted-datapacks)))))}]
     {:legal (not-any? val reasons)
      :reason (join "\n" (filter identity (vals reasons)))
      :description description})))

(defn onesies-legal
  "Returns true if deck is valid under 1.1.1.1 format rules. https://www.reddit.com/r/Netrunner/comments/5238a4/1111_onesies/"
  [sets deck]
  (let [over-one-core (cards-over-one-core deck)
        valid-sets ["Revised Core Set"]
        restricted-sets (group-cards-from-restricted-sets sets valid-sets deck)
        restricted-bigboxes (rest (:bigboxes restricted-sets)) ; one big box is fine
        restricted-datapacks (rest (:datapacks restricted-sets)) ; one datapack is fine
        only-one-offence (>= 1 (apply + (map count [over-one-core restricted-bigboxes restricted-datapacks]))) ; one offence is fine
        example-card (fn [cardlist] (join ", " (map #(get-in % [:card :title]) (take 2 cardlist))))
        reasons (if only-one-offence
                  {}
                  {:onecore (when (not= (count over-one-core) 0)
                              (str "Only one Revised Core Set permitted - check: " (example-card over-one-core)))
                   :bigbox (when (not= (count restricted-bigboxes) 0)
                             (str "Only one Deluxe Expansion permitted - check: " (example-card (second (first restricted-bigboxes)))))
                   :datapack (when (not= (count restricted-datapacks) 0)
                               (str "Only one Datapack permitted - check: " (example-card (second (first restricted-datapacks)))))})]
    {:legal (not-any? val reasons)
     :reason (join "\n" (filter identity (vals reasons)))
     :description "1.1.1.1 format compliant"}))

;; Card and deck validity
(defn allowed?
  "Checks if a card is allowed in deck of a given identity - not accounting for influence"
  [card {:keys [side faction code] :as identity}]
  (and (not= (:type card) "Identity")
       (= (:side card) side)
       (or (not= (:type card) "Agenda")
           (= (:faction card) "Neutral")
           (= (:faction card) faction)
           (is-draft-id? identity))
       (or (not= code "03002") ; Custom Biotics: Engineered for Success
           (not= (:faction card) "Jinteki"))))

(defn valid-deck? [{:keys [identity cards] :as deck}]
  (and (not (nil? identity))
       (>= (card-count cards) (min-deck-size identity))
       (<= (influence-count deck) (id-inf-limit identity))
       (every? #(and (allowed? (:card %) identity)
                     (legal-num-copies? identity %)) cards)
       (or (= (:side identity) "Runner")
           (let [min (min-agenda-points deck)]
             (<= min (agenda-points deck) (inc min))))))

(defn mwl-legal?
  "Returns true if the deck does not contain banned cards or more than one type of restricted card"
  [deck]
  (and (not (contains-banned-cards deck))
       (<= (restricted-card-count deck) 1)))

(defn only-in-rotation?
  "Returns true if the deck doesn't contain any cards outside of current rotation."
  [sets deck]
  (and (every? #(released? sets (:card %)) (:cards deck))
       (released? sets (:identity deck))))

(defn check-deck-status
  "Checks the valid, mwl and rotation keys of a deck-status map to check if the deck is legal, casual or invalid."
  [{:keys [valid mwl rotation]}]
  (cond
    (every? :legal [valid mwl rotation]) "legal"
    (:legal valid) "casual"
    :else "invalid"))

(defn calculate-deck-status
  "Calculates all the deck's validity for the basic deckbuilding rules, as well as various official and unofficial formats.
  Implement any new formats here."
  [deck]
  (let [sets @cards/sets
        valid (valid-deck? deck)
        mwl (mwl-legal? deck)
        rotation (only-in-rotation? sets deck)
        onesies (onesies-legal sets deck)
        cache-refresh (cache-refresh-legal sets deck)
        modded (modded-legal sets deck)]
    {:valid {:legal valid :description "Basic deckbuilding rules"}
     :mwl {:legal mwl :description (:name @cards/mwl)}
     :rotation {:legal rotation :description "Only released and non-rotated cards"}
     :onesies onesies
     :cache-refresh cache-refresh
     ;; Stimhack Online Cache Refresh 7: Latest cycle + Reign and Reverie
     :socr7 (cache-refresh-legal sets deck (concat ["Terminal Directive" "Reign and Reverie"] (sets-in-newest-cycles sets 1))
                                 "Stimhack Online Cache Refresh 7")
     :modded modded}))

(defn trusted-deck-status [{:keys [status date] :as deck}]
  (let [parse-date #?(:clj  #(f/parse (f/formatters :date-time) %)
                      :cljs #(js/Date.parse %))
        deck-date (parse-date date)
        mwl-date (:date_start @cards/mwl)]
    (if (and status
             (> deck-date mwl-date))
      status
      (calculate-deck-status deck))))
