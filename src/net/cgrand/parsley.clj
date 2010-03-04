;   Copyright (c) Christophe Grand. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns net.cgrand.parsley
  "An experimental undocumented parser lib/DSL."
  (:require [net.cgrand.parsley.glr :as core]))

;; Parsley can parse ambiguous grammars and thus returns several results.
;; no support for left recursion (yet)

;; reducer: partial-result * event -> partial-result
;; seed: partial-result
;; stitch: partial-result * partial-result -> partial-result
;; partial-result, seed and stitch must define a monoid
(comment
(defn parser [cont seed reducer stitch result]
  (with-meta [[nil seed cont]] 
    {::seed seed 
     ::reducer #(if (= "" %2) %1 (reducer %1 %2)) 
     ::stitch stitch
     ::result result}))

(defn step 
 [states s]
  (let [{f ::reducer :as m} (meta states)]
    (with-meta (core/interpreter-step f states s) m)))
      
(defn cut [states]
  (let [m (meta states)
        seed (::seed m)]
    (with-meta (map (fn [[_ _ cont]] [cont seed cont]) states) m)))

(defn- group-reduce 
 [k f seed coll]
  (persistent! (reduce #(let [key (k %2)]
                          (assoc! %1 key (f (%1 key seed) %2)))
                 (transient {}) coll)))

(defn results [states]
  (let [{result ::result} (meta states)]
    (distinct (for [[_ r cont] states :when (empty? cont)] (result r))))) 

(defn- element [class contents]
  {:tag class :content contents 
   :length (reduce #(+ %1 (or (:length %2) (count %2))) 0 contents)}) 
)

    
;; DSL support starts here

;; grammar compilation 
;; once evaluated grammar consists of a map of keyword to:
;; * vectors (sequence)
;; * sets (alternatives)
;; * strings and characters (literals)
;; * maps (charsets)
;; * keywords (non-terminal)
;; * lists (special-ops: follow restrictions, rejects)

;; 1. compile-spec turns a sugar-heavy grammar in a sugar-free grammar  
(defmulti #^{:private true} compile-spec type)

;; a vector denotes a sequence, supports postfix operators + ? and *
(defmethod compile-spec clojure.lang.IPersistentVector [specs]
  (reduce #(condp = %2 
             '* (conj (pop %1) [:alt [:seq] (vector :repeat+ (peek %1))]) 
             '+ (conj (pop %1) (vector :repeat+ (peek %1)))
             '? (conj (pop %1) [:alt [:seq] (peek %1)])
             (conj %1 (compile-spec %2))) [:seq] specs))

;; a set denotes an alternative
(defmethod compile-spec clojure.lang.IPersistentSet [s]
  (into [:alt] (map compile-spec s)))

;; a ref to another rule: add support for + ? or * suffixes
(defmethod compile-spec clojure.lang.Keyword [kw]
  (if-let [[_ base suffix] (re-matches #"(.*?)([+*?])" (name kw))] 
    (compile-spec [(keyword base) (symbol suffix)])
    kw))

;; else pass through
(defmethod compile-spec :default [x]
  x)

(defmacro spec [& xs]
  (compile-spec (vec xs)))

;; DSL utils
(defmacro token [& specs]
  `[:token (spec ~@specs)])

(defmacro ?= [& specs]
  `[:follow (spec ~@specs)])

(defmacro ?! [& specs]
  `[:not-follow (spec ~@specs)])

(defn one-of [s]
  (into [:alt] s))

(def any-char {0 core/*max*})

(def $ {-1 0})

;; 2. collect new rules
(defn- genkey [s]
  (-> s gensym keyword))

(def *current-rule*)

(defn- genderived-key [s]
  (genkey (str (if *current-rule* (name *current-rule*) "") s)))

(defmulti collect-rules #(when (vector? %2) (first %2)))

(defmethod collect-rules :repeat+ [token-mode v]
  (let [kw (genderived-key "_repeat+_")
        rule (second v)]
    (cons [v kw [(if token-mode :token :seq) [:alt [:seq kw rule] rule]]] 
      (collect-rules token-mode rule))))   

(defmethod collect-rules :alt [token-mode v]
  (mapcat (partial collect-rules token-mode) (rest v)))

(defmethod collect-rules :seq [token-mode v]
  (mapcat (partial collect-rules token-mode) (rest v)))
  
(defmethod collect-rules :token [token-mode v]
  (collect-rules true (second v)))
  
(defmethod collect-rules :default [token-mode v]
  nil)

(defn collect-new-rules [grammar]
  (let [collected-rules (mapcat (fn [[k v]]
                          (binding [*current-rule* k]
                            (doall (collect-rules false v)))) grammar)
        rewrites (into {} (for [[op k] collected-rules] [op k]))
        new-rules (set (vals rewrites))  
        grammar (into grammar (for [[_ k v] collected-rules
                                    :when (new-rules k)] [k v]))]
    [rewrites grammar]))

;; 3. develop-alts: 
(defmulti develop-alts (fn [rewrites space v]
                         (cond
                           (rewrites v) :maybe-rewrite
                           (vector? v) (first v)
                           :else (type v)))
  :default :maybe-rewrite)

(defmethod develop-alts :seq [rewrites space v]  
  (reduce #(for [x (develop-alts rewrites space %2) xs %1] 
             (concat x (and space (seq x) (seq xs) [space]) xs))
    [()] (rseq (subvec v 1))))

(defmethod develop-alts :alt [rewrites space v]
  (mapcat (partial develop-alts rewrites space) (rest v)))

(defmethod develop-alts :token [rewrites _ v]
  (develop-alts rewrites nil (second v)))
  
(defmethod develop-alts :follow [rewrites space v]
  [[{:follow1 (set (develop-alts rewrites space (second v)))}]]) 

(defmethod develop-alts :not-follow [rewrites space v]
  [[{:follow1 (set (develop-alts rewrites space (second v)))
     :complement true}]]) 

(defmethod develop-alts :maybe-rewrite [rewrites _ x]
  [[(rewrites x x)]])

(defmethod develop-alts String [_ _ s]
  [(map core/ranges s)])
  
(defmethod develop-alts Character [_ _ c]
  [[(core/ranges c)]])
  
(defmethod develop-alts clojure.lang.IPersistentMap [_ _ m]
  [[(apply core/ranges m)]])
  
(defn develop 
 ([grammar] (develop nil {} grammar))
 ([space rewrites grammar]
  (into {} (for [[k v] grammar] [k (set (develop-alts rewrites space v))])))) 

;; 4. remove-empty-prods
(defn- empty-prod? [prod]
  (every? :follow1 prod)) 

(defn split-empty-prods [grammar]
  [(into {}
     (for [[k prods] grammar]
       [k (set (remove empty-prod? prods))]))
   (set
     (for [[k prods] grammar
           :when (some empty-prod? prods)]
       k))])

(defn inline-empty-prods* [grammar]
  (let [[grammar empty-prods] (split-empty-prods grammar)]
    (into {}
      (for [[k prods] grammar]
        [k (-> (set (mapcat 
                 (fn [prod] 
                   (reduce (fn [prods x]
                             (let [xprods (map (partial cons x) prods)]
                               (if (and (empty-prods x) (not= k x))
                                 (concat prods xprods)
                                 xprods))) 
                     [()] (rseq (vec prod)))) prods))
             (disj [k]))]))))  
             
(defn inline-empty-prods [grammar]
  (core/fix-point inline-empty-prods* grammar)) 

;; 5. remove-singletons
(defn remove-singletons [protected? grammar]
  (let [singletons (into {}
                     (for [[k v] grammar
                           :when (and (not (protected? k)) (= 1 (count v)) 
                                   (= 1 (count (first v))))]
                       [k (ffirst v)]))
        singletons (into {}
                     (for [[k v] singletons]
                       [k (core/fix-point #(singletons % %) v)]))
        rewrite-prod (fn this [prod] 
                       (map #(if-let [follow-set (:follow1 %)]
                               (assoc % :follow1 (set (map this follow-set)))  
                               (singletons % %)) prod))]
    (into {}
      (for [[k prods] grammar :when (not (singletons k))]
        [k (set (map rewrite-prod prods))]))))
       


;(defn one-of [& xs]
;  [core/char-range-op (cset xs)])
    
;(defn not-one-of [& xs]
  ;[core/char-range-op (-> xs cset complement-cset)])
    
;(def any-char (not-one-of))

;(def eof [core/op-eof])

(defn- private? [kw]
  (let [s (name kw)]
    (when (.endsWith s "-")
      (keyword (subs s 0 (dec (count s)))))))

;;;;;;;;;;;
(defmacro grammar [options-map & rules]
  (if-not (map? options-map)
    `(grammar {} ~options-map ~@rules)
    (let [{:keys [main seed reducer stitch result] 
           :or {seed `tree-seed 
                reducer `tree-reducer 
                stitch `tree-stitch
                result `tree-result}} options-map 
          rules (partition 2 rules)
          public-rulenames (remove private? (map first rules))
          main (or (:main options-map) (first public-rulenames))
          public-rulenames (set public-rulenames)
          space-name (when (options-map :space) (genkey "space__"))
          main-name (genkey "main__")
          grammar (into {main-name `(spec ~main core/$)}
                    (for [[name specs] rules]
                      [(or (private? name) name) `(spec ~specs)]))
          grammar (if space-name 
                    (assoc grammar space-name `(token ~(options-map :space)))
                    grammar)]
    `[(->> ~grammar collect-new-rules 
         (apply develop ~space-name) inline-empty-prods 
         (remove-singletons (conj ~public-rulenames ~main-name)))
      ~main-name
      ~public-rulenames]))) 

(comment
(def table (apply lr-table 
             (grammar {:main [:A*]
                       :space [" "*]}
               :letter- {\a \z, \A \Z, \0 \9}
               :atom (token :letter+ (?! :letter))
               :A #{:atom [\( :A* \)]})))
(def ttable (first table))
(def sop [[[(second table)] [] [] nil]])
(-> sop (step ttable "cccccc") (step1 ttable -1) prd)
"'<A><atom>cccccc</atom></A>"
(-> sop (step ttable "aa aa") (step1 ttable -1) prd)
"<A><atom>aa</atom></A> <A><atom>aa</atom></A>"
(-> sop (step ttable "(mapcat (partial collectrules tokenmode) (rest v))") (step1 ttable -1) prd)
"<A>(<A><atom>mapcat</atom></A> <A>(<A><atom>partial</atom></A> <A><atom>collectrules</atom></A> <A><atom>tokenmode</atom></A>)</A> <A>(<A><atom>rest</atom></A> <A><atom>v</atom></A>)</A>)</A>"
)