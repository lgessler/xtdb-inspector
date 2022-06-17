(ns xtdb-inspector.page.attr
  "Page to show attributes and browse their values."
  (:require [xtdb.api :as xt]
            [ripley.html :as h]
            [ripley.js :as js]
            [xtdb-inspector.ui :as ui]
            [xtdb-inspector.ui.table :as ui.table]
            [ripley.live.source :as source]
            [ripley.integration.xtdb :as rx]
            [xtdb-inspector.id :as id]))

(defn- request-attr [request]
  (let [{kw' :keyword
         ns' :namespace} (:params request)]
    (when kw'
      (keyword ns' kw'))))

(defn- render-attr-values [{:keys [xtdb-node]} attr]
  (let [[order set-order!] (source/use-state [:val :asc])
        [limit-source set-limit!] (source/use-state 100)
        values (source/computed
                (fn [[order-by order-dir] limit]
                  (with-open [db (xt/open-db xtdb-node)]
                    (into []
                          (map (fn [[e v]]
                                 {:doc e
                                  :val {:id? (id/valid-id? db v)
                                        :v v}}))
                          (xt/q db
                                {:find '[e v]
                                 :where [['e attr 'v]]
                                 :order-by [[(case order-by
                                               :doc 'e
                                               :val 'v)
                                             order-dir]]
                                 :limit (inc limit)}))))
                order limit-source)]
    (h/html
     [:div
      (ui.table/table
       {:columns [{:label "Document" :accessor :doc
                   :render (fn [doc]
                             (ui/format-value (constantly true) doc))
                   ;; Not sortable, ids are often values that don't
                   ;; compare well (like maps or UUIDs)
                   :order-by? false}
                  {:label "Value" :accessor :val
                   :render (fn [{:keys [id? v]}]
                             (ui/format-value (constantly id?) v))}]
        :set-order! set-order!
        :key first}
       values)
      (h/html
       [::h/live (source/computed
                  (fn [values limit]
                    {:count (count values)
                     :limit limit}) values limit-source)
        (fn [{:keys [count limit]}]
          (h/html
           [:div
            [::h/when (> count limit)
             [:div
              [:div.text-xs "Query limited to " limit " items"]
              [:button {:class "px-4 py-2 bg-indigo-50 outline-none border border-indigo-100 rounded text-indigo-500 font-medium active:scale-95 hover:bg-indigo-400 hover:text-white focus:ring-2 focus:ring-indigo-600 focus:ring-offset-2 disabled:bg-gray-400/80 disabled:shadow-none disabled:cursor-not-allowed transition-colors duration-200"
                        :on-click #(set-limit! (* 2 limit))} "Fetch more results"]]]]))])])))

(defn- render-attr-listing [{:keys [xtdb-node]}]
  (ui.table/table
   {:columns [{:label "Attribute" :accessor first
               :render #(ui/link (str "/attr/" (subs (str %) 1))
                                 (pr-str %))}
              {:label "Values count" :accessor second}]
    :key first
    :order [first :asc]}
   (future
     (xt/attribute-stats xtdb-node))))

(defn render [{:keys [xtdb-node request] :as ctx}]
  (if-let [attr (request-attr request)]
    (render-attr-values ctx attr)
    (render-attr-listing ctx)))
