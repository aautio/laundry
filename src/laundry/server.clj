(ns laundry.server
   (:require [compojure.api.sweet :as sweet :refer :all]
             [ring.util.http-response :refer [ok status content-type] :as resp]
             [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
             [ring.adapter.jetty :as jetty]
             [ring.swagger.upload :as upload]
             [schema.core :as s]
             [taoensso.timbre :as timbre :refer [trace debug info warn]]
             [taoensso.timbre.appenders.core :as appenders]
             [clojure.java.shell :refer [sh]]
             [pantomime.mime :refer [mime-type-of]]
             [clojure.string :as string]
             [clojure.set :as set]
             [clojure.java.io :as io]
             [clojure.pprint :refer [pprint]]
             [cheshire.core :as json]
             [ring.util.codec :as codec]))

(s/defschema Status 
   (s/enum "ok" "error"))

(s/defschema LaundryConfig
   {:port s/Num
    :slow-request-warning s/Num
    :temp-directory s/Str
    :checksum-command s/Str
    :pdf2pdfa-command s/Str
    :pdf2png-command s/Str
    :pdf2txt-command s/Str
    :log-level (s/enum :debug :info)})

(defonce config (atom nil))

(defn timestamp []
   (.getTime (java.util.Date.)))
   
(s/defn set-config! [new-configuration :- LaundryConfig]
   (debug "setting config to" new-configuration)
   (reset! config new-configuration))

(defn get-config
   ([key]
      (get-config key nil))
   ([key default]
      (get @config key default)))

(defn not-ok [res]
   (status (ok res) 500))

(defn not-there [res]
   (status (ok res) 404))

(s/defn temp-file-input-stream [path :- s/Str]
   (let [input (io/input-stream (io/file path))]
      (proxy [java.io.FilterInputStream] [input]
         (close []
            (proxy-super close)
            (io/delete-file path)))))

;;; Handler

(s/defschema DigestAlgorithm
   (s/enum "sha256"))

;; pdf/a converter
(s/defn api-pdf2pdfa [env, tempfile :- java.io.File]
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".pdf")
         res (sh (:pdf2pdfa-command env) path out)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "application/pdf")
         (not-ok "pdf2pdfa conversion failed"))))

;; pdf → txt conversion
(s/defn api-pdf2txt [env, tempfile :- java.io.File]
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".txt")
         res (sh (:pdf2txt-command env) path out)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "text/plain")
         (not-ok "pdf2txt conversion failed"))))

;; previewer of first page
(s/defn api-pdf2png [env, tempfile :- java.io.File]
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".png")
         res (sh (:pdf2png-command env) path out)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "image/png")
         (do
            (warn "pdf preview failed: " res)
            (not-ok "pdf preview failed")))))

(s/defn api-checksum [tempfile :- java.io.File, digest :- DigestAlgorithm]
   (let [res (sh (get-config :checksum-command) (.getAbsolutePath tempfile) digest)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (ok (:out res))
         (not-ok "digest computation failed"))))

(defn make-pdf-routes [env]
   (sweet/context "/pdf" []
      
      (POST "/pdf-preview" []
         :summary "attempt to convert first page of a PDF to PNG"
         :multipart-params [file :- upload/TempFileUpload]
         :middleware [upload/wrap-multipart-params]
         (let [tempfile (:tempfile file)
               filename (:filename file)]
            (info "PDF previewer received " filename "(" (:size file) "b)")
            (.deleteOnExit tempfile) ;; cleanup if VM is terminated
            (api-pdf2png env tempfile)))
         
      (POST "/pdf2txt" []
         :summary "attempt to convert a PDF file to TXT"
         :multipart-params [file :- upload/TempFileUpload]
         :middleware [upload/wrap-multipart-params]
         (let [tempfile (:tempfile file)
               filename (:filename file)]
            (info "PDF2TXT converter received " filename "(" (:size file) "b)")
            (.deleteOnExit tempfile) ;; cleanup if VM is terminated
            (api-pdf2txt env tempfile)))
         
      (POST "/pdf2pdfa" []
         :summary "attempt to convert a PDF file to PDF/A"
         :multipart-params [file :- upload/TempFileUpload]
         :middleware [upload/wrap-multipart-params]
         (let [tempfile (:tempfile file)
               filename (:filename file)]
            (info "PDF converter received " filename "(" (:size file) "b)")
            (.deleteOnExit tempfile) ;; cleanup if VM is terminated
            (api-pdf2pdfa env tempfile)))))
         
(defn make-api-handler [env]
   (let [pdf-api (make-pdf-routes env)]
      (api
         {:swagger
            {:ui "/api-docs"
             :spec "/swagger.json"
             :data {:info {:title "Laundry API"
                             :description ""}}}}
   
         (undocumented
           (GET "/" []
             (resp/temporary-redirect "/index.html")))

            
         (context "/api" []
   
            (GET "/alive" []
               :summary "check whether server is running"
               (ok "yes"))
   
            (GET "/config" []
               :summary "get current laundry configuration"
               :return LaundryConfig
               (ok @config))
            
            pdf-api
         
            (POST "/digest/sha256" []
               :summary "compute a SHA256 digest for posted data"
               :multipart-params [file :- upload/TempFileUpload]
               :middleware [upload/wrap-multipart-params]
               (let [tempfile (:tempfile file)
                     filename (:filename file)]
                  (info "SHA256 received " filename "(" (:size file) "b)")
                  (.deleteOnExit tempfile)
                  (api-checksum tempfile "sha256")))))))

(defn request-time-logger [handler]
   (fn [req]
      (let [start (timestamp)
            res (handler req)
            elapsed (- (timestamp) start)]
         (if (> elapsed (get-config :slow-request-warning 10000))
            (warn (str "slow request: " (:uri req) 
                  " took " elapsed "ms")))
         res)))

(defn make-handler [env]
  (-> (make-api-handler env)
      request-time-logger
      (wrap-defaults (-> (assoc-in site-defaults [:security :anti-forgery] false)
                         (assoc-in [:params :multipart] false)))))

;;; Startup and shutdown

(defonce server (atom nil))

(defn stop-server []
   (when-let [s @server]
      (info "stopping server")
      (.stop s)
      (reset! server nil)))

(defn start-laundry [handler conf]
   (when server
      (stop-server))
   (stop-server)
   (reset! server (jetty/run-jetty handler conf))
   (when server
     (info "Laundry is running at port" (get-config :port))))

(s/defn ^:always-validate start-server [conf :- LaundryConfig]
   ;; update config
   (set-config! conf)
   ;; configure logging accordingly
   (timbre/merge-config!
      {:appenders
         {:spit
            (appenders/spit-appender
               {:fname (get-config :logfile "laundry.log")})}})
   (timbre/set-level!
      (get-config :log-level :info))
   (info "start-server, config " @config)
   ;; configure logging
   (start-laundry (make-handler conf)
      {:port (get-config :port 8080)
       :join? false}))


;;; Dev mode entry

(defn reset [] 
   (if server
      (do
         (println "Stopping laundry")
         (stop-server)))
   (require 'laundry.server :reload)
   ;; todo: default config should come from command line setup
   (start-server
      {:slow-request-warning 500
       :port 9001
       :temp-directory "/tmp"
       :checksum-command "programs/checksum"
       :pdf2pdfa-command "programs/pdf2pdfa"
       :pdf2png-command "programs/pdf2png"
       :pdf2txt-command "programs/pdf2txt"
       :log-level :info}))

(defn go []
   (info "Go!")
   (reset))

