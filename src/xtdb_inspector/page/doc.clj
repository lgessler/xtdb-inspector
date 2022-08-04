(ns xtdb-inspector.page.doc
  "Page that displays a single document."
  (:require [ripley.html :as h]
            [xtdb-inspector.id :as id]
            [xtdb-inspector.ui :as ui]
            [xtdb.api :as xt]
            [ripley.live.source :as source]
            [clojure.set :as set]
            [ripley.js :as js]
            [ripley.integration.xtdb :as rx]
            [ripley.live.protocols :as p]
            [clojure.java.io :as io]
            [xtdb-inspector.ui.table :as ui.table]
            [xtdb-inspector.ui.edn :as ui.edn])
  (:import (java.time LocalDate LocalTime)))

;; PENDING: we could have a live collection
;; for the history and listen to changes as they happen.
;;
;; The whole page could be live and show the current doc
;; and the history. Any changes in db could be listened to
;; and automatically reflected here.

(defn changes [history]
  (loop [old nil
         acc []
         [h & history] history]
    (if-not h
      acc
      (let [new (dissoc (::xt/doc h) :xt/id)
            all-keys (set/union (set (keys old))
                                (set (keys new)))
            history-entry
            {:time (::xt/tx-time h)
             :changes (into {}
                            (keep (fn [k]
                                   (let [old-value (get old k ::no-value)
                                         new-value (get new k ::no-value)]
                                     (when (not= old-value new-value)
                                       [k {::from old-value ::to new-value}]))))
                            all-keys)}]
        (recur new
               (conj acc history-entry)
               history)))))


(defn attr-val-row [attr val-fn]
  (h/html
   [:tr.hover:bg-gray-100
    [:td.px-2.py-2.font-semibold {:class "w-1/3"} attr]
    [:td.px-2.py-2
     (val-fn)]]))

(defn entity-history [db id ]
  (let [history (xt/entity-history db id :asc {:with-docs? true})
        changes (reverse (changes history))
        initial (last changes)]
    (h/html
     [:div.entity-history.pt-4
      [:h3 "History"]
      [::h/for [{:keys [time changes] :as chg}  changes
                :let [time (ui/format-inst time)]]
       [:div.history-entry
        [:hr]
        [:div.font-bold time
         [::h/when (= chg initial)
          " (initial document)"]]
        [:table.font-mono {:class "w-9/12"}
         [::h/for [[key val] (sort-by first changes)
                   :let [key-name (pr-str key)
                         old-val (when (not= ::no-value (::from val))
                                   (pr-str (::from val)))
                         new-val (when (not= ::no-value (::to val))
                                   (::to val))]]
          (attr-val-row
           key-name
           (fn []
             (h/html
              [:span
               [::h/when old-val
                [:div.line-through old-val]]
               [::h/when new-val
                [:div (ui/format-value (constantly false) new-val)]]])))]]]]])))

(defn links-to [xtdb id]
  (let [attrs
        ;; PENDING: we could also keep a set of all
        ;; attributes of id values we encounter when
        ;; displaying docs and persisting it.
        ;; But perhaps this is fast enough?
        (disj (into #{}
                    (map key)
                    (xt/attribute-stats xtdb))
              :xt/id)]
    (with-open [db (xt/open-db xtdb)]
      (into []
            (mapcat
             (fn [attr]
               (for [[from]
                     (xt/q db {:find ['?e]
                               :where [['?e attr 'id]]
                               :in ['id]} id)]
                 [attr from])))
            attrs))))

(defn render-links-to [links]
  (h/html
   [:div
    [:table.font-mono {:class "w-9/12"}
     [:thead
      [:tr
       [:td "Attribute"]
       [:td "Document"]]]
     [:tbody
      [::h/for [[attr from] links
                :let [attr-name (pr-str attr)]]
       (attr-val-row attr-name
                     #(ui/format-value (constantly true) from))]]]]))


(defn- update-doc! [xtdb-node entity-id attribute value]
  ;; NOTE: this doesn't check if entity has changed
  (xt/submit-tx
   xtdb-node
   [[::xt/put
     (merge
      (xt/entity (xt/db xtdb-node) entity-id)
      {:xt/id entity-id ; if entity doesn't exist yet
       attribute value})]]))

(defn- attr-row [key-fn content-fn]
  (h/html
   [:tr.hover:bg-gray-100
    [:td.px-2.py-2.font-semibold.align-top {:class "w-1/3"}
     (key-fn)]
    [:td.px-2.py-2.hover-trigger
     (content-fn)]]))

(defn new-attr-row [xtdb-node id]
  (let [[rerender-source set-rerender!] (source/use-state 0)
        rerender! #(set-rerender! (inc (p/current-value rerender-source)))]
    (h/html
     [::h/live
      rerender-source
      (fn [_]
        (let [[attr-name set-attr-name!] (source/use-state "")
              [value-type set-value-type!] (source/use-state :not-set)
              editor-types (-> ui/editor-widget-for
                               methods
                               (dissoc :default)
                               keys)]
          (attr-row
           (fn []
             ;; We don't want live component here, no need to rerender when it changes
             (ui/input "text" "" set-attr-name!
                       :placeholder "New attr kw"))
           (fn []
             (h/html
              [:div.form-control
               [:div.input-group.input-group-sm


                [:select.select.select-sm.select-bordered
                 {:on-change (js/js #(set-value-type!
                                      (or (first (filter (fn [t]
                                                           (= % (ui/short-class-name t)))
                                                         editor-types))
                                          :edn))
                                    "event.target.value")}
                 [:option {:value ""} "Type"]
                 [:option {:value "EDN"} "EDN"]
                 [::h/for [cls editor-types
                           :let [class-name (ui/short-class-name cls)]]
                  [:option {:value class-name} class-name]]]

                [::h/live value-type
                 (fn [type]
                   (if (= type :not-set)
                     (h/html [:span ""])
                     (ui/editor-widget-for
                      type ::ui/empty
                      (fn [to]
                        (rerender!)
                        (update-doc! xtdb-node id
                                     (ui/parse-edn (p/current-value attr-name))
                                     to)))))]]])))))])))

(declare render-doc-data doc-source)

(defn- inline-doc-view
  "Component to allow drilling down to nested documents inline without
  navigating to them."
  [xtdb-node _db id]
  (let [[show set-show!] (source/use-state false)]
    (h/html
     [:div {:class [::h/live (source/computed #(str "collapse collapse-"
                                                    (if % "open" "close"))
                                              show)]}
      [:div ;; .collapse-title has way too much padding
       (ui/format-value (constantly true) id)

       [::h/live show
        #(h/html [:button.btn.btn-square.btn-xs.ml-1.btn-info
                  {:on-click (partial set-show! (not %))}
                  [::h/if % "-" "+"]])]]
      [:div.collapse-content
       [::h/when show
        (render-doc-data xtdb-node id (doc-source xtdb-node id))]]])))

(defn- render-editable-value [xtdb-node db entity-id [k v]]
  (let [[edit? set-edit!] (source/use-state false)]
    (h/html
     [::h/live edit?
      (fn [edit?]
        (if-not edit?
          (let [id (when-not (vector? v)
                     (id/valid-id? db v))]
            (h/html
             [:div.hover-trigger
              [:div.flex
               [::h/if id
                (inline-doc-view xtdb-node db v)
                (ui/format-value (if (vector? v)
                                   (partial id/valid-id? db)
                                   (constantly id)) v)]
               [:div.flex-grow.flex.justify-end.items-start
                [:button.hover-target.fixed.bg-blue-500.rounded.px-1
                 {:on-click #(set-edit! true)}
                 "edit"]]]]))
          (ui/editor-widget-for (type v) v
                                (partial update-doc! xtdb-node
                                         entity-id k))))])))

(defn render-doc-id-header [id]
  (let [id-str (pr-str id)]
    (h/html
     [:h3.bg-gray-300 "Document   "
      [:span.font-mono id-str]
      [:input#doc-id {:style "display: none;" :disabled true :value id-str}]
      [:button.mx-2.px-2.rounded-full.bg-blue-200
       {:on-click "s=document.getElementById('doc-id');s.select();navigator.clipboard.writeText(s.value);"}
       "copy"]])))

(defn- render-doc-data [xtdb-node id entity-source]
  (ui.table/table
   {:key key
    :class "table table-compact table-zebra w-full"
    :columns [{:label "Attribute" :accessor key
               :render ui.edn/edn}
              {:label "Value" :accessor val
               :render-full (partial render-editable-value xtdb-node
                                     (xt/db xtdb-node)
                                     id)}]
    :order [key :asc]
    :render-after #(new-attr-row xtdb-node id)}
   (source/computed
    (comp seq #(dissoc % :xt/id))
    entity-source)))

(defn doc-source [xtdb-node id]
  (source/computed
   ffirst
   (rx/q {:node xtdb-node}
         '{:find [(pull e [*])]
           :in [e]} id)))

(defn render [{:keys [xtdb-node request] :as _ctx}]
  (let [id (some-> request :params :doc-id id/read-doc-id)
        entity-source (doc-source xtdb-node id)
        [show-history-source set-show-history!] (source/use-state false)]
    (h/html
     [:div
      (render-doc-id-header id)
      (render-doc-data xtdb-node id entity-source)

      [:h3.bg-gray-300 "Links from other documents"]
      [::h/live (future (links-to xtdb-node id))
       render-links-to]

      [::h/live show-history-source
       (fn [show?]
         (h/html
          [:div
           [::h/if show?
            (entity-history (xt/db xtdb-node) id)
            [:button {:on-click #(set-show-history! true)}
             "Show history"]]]))]])))

(defn render-form [ctx]
  (let [doc (atom nil)
        set-doc! (fn [value]
                   (binding [*read-eval* false]
                     (reset! doc (read-string value))))]
    (h/html
     [:div.flex.flex-col.m-4
      [:div "Insert XTDB document id (" [:span.font-mono ":xt/id"] ") as EDN:"]
      [:div
       [:div.form-control
        [:div.input-group
         [:input#doc.input {:name "doc"}]
         [:button.btn.btn-primary
          {:on-click (js/js set-doc! (js/input-value "doc"))}
          "Go"]]]]
      [:span.font-light
       "Examples: 123  :hello  \"some-doc\" "]
      (js/eval-js-from-source
       (source/c=
        (when %doc
          (str "window.location.pathname += \"/"
               (id/doc-id-param %doc) "\""))))]))  )
