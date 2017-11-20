(ns laundry.pdf
   (:require [compojure.api.sweet :as sweet :refer :all]
             [ring.util.http-response :refer [ok status content-type] :as resp]
             [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
             [ring.swagger.upload :as upload]
             [taoensso.timbre :as timbre :refer [trace debug info warn]]
             [schema.core :as s]
             [clojure.java.shell :refer [sh]]
             [laundry.machines :as machines]
             [pantomime.mime :refer [mime-type-of]]
             [clojure.string :as string]
             [clojure.set :as set]
             [clojure.java.io :as io]
             [clojure.pprint :refer [pprint]]))

(defn not-ok [res]
   (status (ok res) 500))

(s/defn temp-file-input-stream [path :- s/Str]
   (let [input (io/input-stream (io/file path))]
      (proxy [java.io.FilterInputStream] [input]
         (close []
            (proxy-super close)
            (io/delete-file path)))))

;; pdf/a converter
(s/defn api-pdf2pdfa [env, tempfile :- java.io.File]
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".pdf")
         res (sh (str (:tools env) "/bin/pdf2pdfa") path out)] 
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "application/pdf")
         (not-ok "pdf2pdfa conversion failed"))))

;; pdf → txt conversion
(s/defn api-pdf2txt [env, tempfile :- java.io.File]
   (info "Running, tools are at " (:tools env))
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".txt")
         res (sh (str (:tools env) "/bin/pdf2txt") path out)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "text/plain")
         (not-ok "pdf2txt conversion failed"))))

;; previewer of first page
(s/defn api-pdf2jpeg [env, tempfile :- java.io.File]
   (let [path (.getAbsolutePath tempfile)
         out  (str (.getAbsolutePath tempfile) ".jpeg")
         res (sh (str (:tools env) "/bin/pdf2jpeg") path out)]
      (.delete tempfile)
      (if (= (:exit res) 0)
         (content-type 
            (ok (temp-file-input-stream out))
             "image/jpeg")
         (do
            (warn "pdf preview failed: " res)
            (not-ok "pdf preview failed")))))

(machines/add-api-generator! 
   (fn [env] 
      (sweet/context "/pdf" []
         
         (POST "/pdf-preview" []
            :summary "attempt to convert first page of a PDF to PNG"
            :multipart-params [file :- upload/TempFileUpload]
            :middleware [upload/wrap-multipart-params]
            (let [tempfile (:tempfile file)
                  filename (:filename file)]
               (info "PDF previewer received " filename "(" (:size file) "b)")
               (.deleteOnExit tempfile) ;; cleanup if VM is terminated
               (api-pdf2jpeg env tempfile)))
            
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
               (api-pdf2pdfa env tempfile))))))
         
