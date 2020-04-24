(ns frontend.db
  (:require [datascript.core :as d]
            [frontend.util :as util]
            [medley.core :as medley]
            [datascript.transit :as dt]
            [frontend.format :as format]
            [frontend.format.org-mode :as org]
            [frontend.format.org.block :as block]
            [frontend.state :as state]
            [clojure.string :as string]
            [clojure.set :as set]
            [frontend.utf8 :as utf8]))

;; TODO: Create a database for each repo.
;; Multiple databases
(defn get-repo-path
  [url]
  (->> (take-last 2 (string/split url #"/"))
       (string/join "/")))

(defn datascript-db
  [repo]
  (str "logseq-db/" (get-repo-path repo)))

(defonce conns
  (atom {}))

(defn get-conn
  ([]
   (get-conn (state/get-current-repo) true))
  ([repo]
   (get-conn repo true))
  ([repo deref?]
   (when-let [conn (get @conns (datascript-db repo))]
     (if deref?
       @conn
       conn))))

;; A page can corresponds to multiple files (same title),
;; a month journal file can have multiple pages,
;; also, each heading can be treated as a page if we support
;; "zoom edit".
(def schema
  {:db/ident        {:db/unique :db.unique/identity}

   ;; user
   :me/name  {}
   :me/email {}
   :me/avatar {}

   ;; repo
   :repo/url        {:db/unique :db.unique/identity}
   :repo/cloning?   {}
   :repo/cloned?    {}

   ;; file
   :file/path       {:db/unique :db.unique/identity}
   :file/content    {}
   ;; TODO: calculate memory/disk usage
   ;; :file/size       {}

   :page/name       {:db/unique      :db.unique/identity}
   :page/file       {:db/valueType   :db.type/ref}
   :page/journal?   {}
   :page/journal-day {}

   ;; heading
   :heading/uuid   {:db/unique      :db.unique/identity}
   :heading/file   {:db/valueType   :db.type/ref}
   :heading/page   {:db/valueType   :db.type/ref}
   :heading/ref-pages {:db/valueType   :db.type/ref
                       :db/cardinality :db.cardinality/many}
   :heading/content {}
   :heading/anchor {}
   :heading/marker {}
   :heading/priority {}
   :heading/level {}
   :heading/tags {:db/valueType   :db.type/ref
                  :db/cardinality :db.cardinality/many}
   ;; :heading/parent {:db/valueType   :db.type/ref}

   ;; tag
   :tag/name       {:db/unique :db.unique/identity}

   ;; task
   :task/scheduled {:db/index       true}
   :task/deadline  {:db/index       true}
   })

;; transit serialization

(defn db->string [db]
  (dt/write-transit-str db))

(defn string->db [s]
  (dt/read-transit-str s))

;; persisting DB between page reloads
(defn persist [repo db]
  (js/localStorage.setItem (datascript-db repo) (db->string db)))

(defn reset-conn! [conn db]
  (reset! conn db))

(defn transact!
  [tx-data]
  (d/transact! (get-conn (state/get-current-repo) false) tx-data))

;; (new TextEncoder().encode('foo')).length
;; (defn db-size
;;   [repo]
;;   (when-let [store (js/localStorage.getItem (datascript-db repo))]
;;     (let [bytes (.-length (.encode (js/TextEncoder.) store))]
;;       (/ bytes 1000))))

(defn entity
  [id-or-lookup-ref]
  (d/entity (d/db (get-conn (state/get-current-repo) false)) id-or-lookup-ref))

(defn kv
  [key value]
  {:db/id -1
   :db/ident key
   key value})

;; TODO: added_at, started_at, schedule, deadline
(def qualified-map
  {:file :heading/file
   :anchor :heading/anchor
   :title :heading/title
   :marker :heading/marker
   :priority :heading/priority
   :level :heading/level
   :timestamps :heading/timestamps
   :children :heading/children
   :tags :heading/tags
   :meta :heading/meta
   :content :heading/content
   :page :heading/page
   :ref-pages :heading/ref-pages
   ;; :parent :heading/parent
   })

(defn- safe-headings
  [headings]
  (mapv (fn [heading]
          (let [heading (-> (util/remove-nils heading)
                            (assoc :heading/uuid (d/squuid)))]
            (medley/map-keys
             (fn [k] (get qualified-map k k))
             heading)))
        headings))

;; queries

(defn- distinct-result
  [query-result]
  (-> query-result
      seq
      flatten
      distinct))

(def seq-flatten (comp flatten seq))

(defn get-all-tags
  [repo]
  (distinct-result
   (d/q '[:find ?tags
          :where
          [?h :heading/tags ?tags]]
     (get-conn repo))))

(defn- remove-journal-files
  [files]
  (remove
   (fn [file]
     (string/starts-with? file "journals/"))
   files))

(defn get-pages
  [repo]
  (->> (d/q '[:find ?page-name
              :where
              [?page :page/name ?page-name]]
         (get-conn repo))
       (map first)
       ;; distinct
       ))

(defn get-files-headings
  [repo-url paths]
  (let [paths (set paths)
        pred (fn [db e]
               (contains? paths e))]
    (-> (d/q '[:find ?heading
               :in $ ?pred
               :where
               [?file :file/path ?path]
               [(?pred $ ?path)]
               [?heading :heading/file ?file]]
          (get-conn repo-url) pred)
        seq-flatten)))

(defn delete-headings
  [repo-url files]
  (when (seq files)
    (let [headings (get-files-headings repo-url files)]
      (mapv (fn [eid] [:db.fn/retractEntity eid]) headings))))

(defn delete-files
  [files]
  (mapv (fn [path] [:db.fn/retractEntity [:file/path path]]) files))

(defn get-file-headings
  [repo-url path]
  (-> (d/q '[:find ?heading
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?heading :heading/file ?file]]
        (get-conn repo-url) path)
      seq-flatten))

(defn delete-file-headings!
  [repo-url path]
  (let [headings (get-file-headings repo-url path)]
    (mapv (fn [eid] [:db.fn/retractEntity eid]) headings)))

(defn reset-contents-and-headings!
  [repo-url contents headings-pages delete-files delete-headings]
  (let [file-contents (map (fn [[file content]]
                             (when content
                               {:file/path file
                                :file/content content}))
                        contents)
        all-data (-> (concat delete-files delete-headings file-contents headings-pages)
                     (util/remove-nils))]
    (transact! all-data)))

(defn get-headings-by-tag
  [repo tag]
  (let [pred (fn [db tags]
               (some #(= tag %) tags))]
    (d/q '[:find (flatten (pull ?h [*]))
           :in $ ?pred
           :where
           [?h :heading/tags ?tags]
           [(?pred $ ?tags)]]
      (get-conn repo) pred)))

(defn set-key-value
  [key value]
  (transact! [(kv key value)]))

(defn get-key-value
  ([key]
   (get-key-value (d/db (get-conn (state/get-current-repo) false))))
  ([db key]
   (some-> (d/entity db key)
           key)))

(defn sort-by-pos
  [headings]
  (sort-by (fn [heading]
             (get-in heading [:heading/meta :pos]))
           headings))

(defn get-file-by-concat-headings
  ([path]
   (get-file-by-concat-headings (state/get-current-repo)
                                path))
  ([repo-url path]
   (->> (d/q '[:find (pull ?heading [*])
               :in $ ?path
               :where
               [?file :file/path ?path]
               [?heading :heading/file ?file]]
          (get-conn repo-url) path)
        seq-flatten
        sort-by-pos)))

(defn get-page-headings
  ([page]
   (get-page-headings (state/get-current-repo)
                      page))
  ([repo-url page]
   (->> (d/q '[:find (pull ?heading [*])
               :in $ ?page
               :where
               [?p :page/name ?page]
               [?heading :heading/page ?p]]
          (get-conn repo-url) page)
        seq-flatten
        sort-by-pos)))

;; TODO: quite slow
(defn get-heading-with-children
  [heading-uuid]
  (let [repo-url (state/get-current-repo)
        heading (entity [:heading/uuid heading-uuid])
        heading-level (:heading/level heading)
        pred (fn [db uuid meta level child-meta child-level]
               (or
                (= uuid heading-uuid)
                (< (:pos meta) (:pos child-meta))))]
    (->> (d/q '[:find (pull ?child [*])
                :in $ ?heading-uuid ?pred
                :where
                [?heading :heading/uuid ?heading-uuid]
                [?heading :heading/file ?file]
                [?child   :heading/file ?file]
                [?child   :heading/uuid ?child-uuid]
                [?heading :heading/level ?level]
                [?heading :heading/meta ?meta]
                [?child   :heading/meta ?child-meta]
                [?child   :heading/level ?child-level]
                [(?pred $ ?child-uuid ?meta ?level ?child-meta ?child-level)]]
           (get-conn repo-url) heading-uuid pred)
         seq-flatten
         sort-by-pos
         (take-while (fn [{:heading/keys [uuid level meta]}]
                       (or
                        (= uuid heading-uuid)
                        (> level heading-level)))))))

(defn mark-repo-as-cloned
  [repo-url]
  (transact!
    [{:repo/url repo-url
      :repo/cloned? true}]))

(defn cloned?
  [repo-url]
  (->
   (d/q '[:find ?cloned
          :in $ ?repo-url
          :where
          [?repo :repo/url ?repo-url]
          [?repo :repo/cloned? ?cloned]]
     (get-conn repo-url) repo-url)
   first))

(defn get-page-name
  [file ast]
  (when-let [heading (first (filter block/heading-block? ast))]
    (when-let [title (:title (second heading))]
      ;; FIXME:
      (str title)
      (first (string/split file #"\.")))))

(defn valid-journal-title?
  [title]
  (and title
       (not (js/isNaN (js/Date.parse title)))))

(defn get-heading-content
  [utf8-content heading]
  (let [meta (:meta heading)]
    (if-let [end-pos (:end-pos meta)]
      (utf8/substring utf8-content
                      (:pos meta)
                      end-pos)
      (utf8/substring utf8-content
                      (:pos meta)))))

;; file

(defn journal-page-name->int
  [page-name]
  (let [[m d y] (-> (last (string/split page-name #", "))
                    (string/split #"/"))]
    (util/parse-int (str y m d))))

(defn extract-pages-and-headings
  [file content utf8-content journal? pages-fn]
  (let [ast (org/->clj content org/config-with-line-break)
        headings (block/extract-headings ast (utf8/length utf8-content))
        pages (pages-fn headings ast)
        ref-pages (atom #{})
        headings (mapcat
                  (fn [[page headings]]
                    (if page
                      (map (fn [heading]
                             (let [heading-ref-pages (seq (:ref-pages heading))]
                               (when heading-ref-pages
                                 (swap! ref-pages set/union (set heading-ref-pages)))
                               (-> heading
                                   (dissoc :ref-pages)
                                   (assoc :heading/content (get-heading-content utf8-content heading)
                                          :heading/file [:file/path file]
                                          :heading/page [:page/name (string/capitalize page)]
                                          :heading/ref-pages (mapv
                                                              (fn [page]
                                                                {:page/name (string/capitalize page)})
                                                              heading-ref-pages)))))
                        headings)))
                  pages)
        headings (safe-headings headings)
        pages (map
                (fn [page]
                  {:page/name (if page
                                (string/capitalize page)
                                (string/capitalize (first (string/split #"\." file))))
                   :page/file [:file/path file]
                   :page/journal? journal?
                   :page/journal-day (if journal?
                                       (journal-page-name->int page)
                                       0)})
                (map first pages))
        pages (concat
               pages
               (map
                 (fn [page]
                   {:page/name (string/capitalize page)})
                 @ref-pages))]
    (vec
     (->> (concat
           pages
           headings)
          (remove nil?)))))

;; check journal formats and report errors
(defn extract-headings-pages
  [file content utf8-content]
  (if (string/blank? content)
    []
    (let [journal? (string/starts-with? file "journals/")]
      (if journal?
        (extract-pages-and-headings
         file content utf8-content true
         (fn [headings _ast]
           (loop [pages {}
                  last-page-name nil
                  headings headings]
             (if (seq headings)
               (let [[{:keys [level title] :as heading} & tl] headings]
                 (if (and (= level 1)
                          (when-let [title (last (first title))]
                            (valid-journal-title? title)))
                   (let [page-name (last (first title))
                         new-pages (assoc pages page-name [heading])]
                     (recur new-pages page-name tl))
                   (let [new-pages (update pages last-page-name (fn [headings]
                                                                  (vec (conj headings heading))))]
                     (recur new-pages last-page-name tl))))
               pages))))
        (extract-pages-and-headings
         file content utf8-content false
         (fn [headings ast]
           [[(get-page-name file ast) headings]]))))))

(defn get-all-files-content
  [repo-url]
  (d/q '[:find ?path ?content
         :where
         [?file :file/content ?content]
         [?file :file/path ?path]]
    (get-conn repo-url)))

(defn extract-all-headings-pages
  [contents]
  (vec
   (mapcat
    (fn [[file content] contents]
      (when content
        (let [utf8-content (utf8/encode content)]
          (extract-headings-pages file content utf8-content))))
    contents)))

(defn reset-file!
  [repo-url file content]
  (let [utf8-content (utf8/encode content)
        file-content [{:file/path file
                       :file/content content}]
        delete-headings (delete-file-headings! repo-url file)
        headings-pages (extract-headings-pages file content utf8-content)]
    (transact! (concat file-content delete-headings headings-pages))))

(defn get-file-content
  [repo-url path]
  (->> (d/q '[:find ?content
              :in $ ?path
              :where
              [?file :file/path ?path]
              [?file :file/content ?content]]
         (get-conn repo-url) path)
       (map first)
       first))

(defn get-file
  [path]
  (->
   (d/q '[:find ?content
          :in $ ?path
          :where
          [?file :file/path ?path]
          [?file :file/content ?content]]
     (get-conn)
     path)
   ffirst))

;; marker should be one of: TODO, DOING, IN-PROGRESS
;; time duration
(defn get-agenda
  ([]
   (get-agenda :week))
  ([time]
   (let [duration (case time
                    :today []
                    :week  []
                    :month [])]
     (->
      (d/q '[:find (pull ?h [*])
             :where
             (or [?h :heading/marker "TODO"]
                 [?h :heading/marker "DOING"]
                 [?h :heading/marker "IN-PROGRESS"]
                 ;; [?h :heading/marker "DONE"]
                 )]
        (get-conn))
      seq-flatten))))

(defn get-current-journal-path
  []
  (let [{:keys [year month]} (util/get-date)]
    (util/journals-path year month)))

(defn get-journal
  ([]
   (get-journal (util/journal-name)))
  ([page-name]
   [page-name (get-page-headings page-name)]))

;; cache this
(defn get-latest-journals
  ([n]
   (get-latest-journals (state/get-current-repo) n))
  ([repo-url n]
   (let [date (js/Date.)
         _ (.setDate date (- (.getDate date) (dec n)))
         date->int (fn [date]
                     (util/parse-int
                      (string/replace (util/ymd date) "/" "")))
         before-day (date->int date)
         today (date->int (js/Date.))
         pages (->>
                (d/q '[:find ?page-name ?journal-day
                       :in $ ?before-day ?today
                       :where
                       [?page :page/name ?page-name]
                       [?page :page/journal? true]
                       [?page :page/journal-day ?journal-day]
                       [(<= ?before-day ?journal-day ?today)]
                       ]
                  (get-conn repo-url)
                  before-day
                  today)
                (sort-by last)
                (reverse)
                (map first))]
     (mapv
      (fn [page]
        [page (get-page-headings repo-url page)])
      pages))))

(defn me-tx
  [db {:keys [name email avatar repos]}]
  (util/remove-nils {:me/name name
                     :me/email email
                     :me/avatar avatar}))

(defn with-dummy-heading
  [headings]
  (when (seq headings)
    (let [last-heading (last headings)
          end-pos (get-in last-heading [:heading/meta :end-pos])
          dummy (merge last-heading
                       (let [uuid (d/squuid)]
                         {:heading/uuid uuid
                          :heading/title ""
                          :heading/content "** "
                          :heading/level 2
                          :heading/priority nil
                          :heading/anchor (str uuid)
                          :heading/meta {:pos end-pos
                                         :end-pos nil}
                          :heading/children nil
                          :heading/dummy? true
                          :heading/marker nil}))]
      (vec (concat headings [dummy])))))

;; TODO: Sorted by last-modified-time
(defn get-page-referenced-headings
  [page]
  (let [page-name (string/capitalize page)]
    (-> (d/q '[:find (pull ?heading [*])
               :in $ ?page-name
               :where
               [?page :page/name ?page-name]
               [?heading :heading/ref-pages ?page]]
          (get-conn)
          page-name)
        seq-flatten)))

(defn start-db-conn!
  [me repo listen-handler]
  (let [db-name (datascript-db repo)
        db-conn (d/create-conn schema)]
    (swap! conns assoc db-name db-conn)
    (listen-handler repo db-conn)
    (d/transact! db-conn [(me-tx (d/db db-conn) me)])))

(defn restore! [{:keys [repos] :as me} listen-handler]
  (doseq [{:keys [id url]} repos]
    (let [repo url
          db-name (datascript-db repo)
          db-conn (d/create-conn schema)]
      (swap! conns assoc db-name db-conn)
      (if-let [stored (js/localStorage.getItem db-name)]
        (let [stored-db (string->db stored)
              attached-db (d/db-with stored-db [(me-tx stored-db me)])]
          (when (= (:schema stored-db) schema) ;; check for code update
            (reset-conn! db-conn attached-db)))
        (d/transact! db-conn [(me-tx (d/db db-conn) me)]))
      (listen-handler repo db-conn))))
