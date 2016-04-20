(ns puppetlabs.ring-middleware.core
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.ring-middleware.common :as common]
            [puppetlabs.ssl-utils.core :as ssl-utils]
            [ring.middleware.cookies :as cookies]
            [ring.util.response :as rr]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+]])
  (:import (clojure.lang IFn)
           (java.util.regex Pattern)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Schemas

(def ResponseType (schema/enum :json :plain))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Helpers

(defn json-response [status body]
  (-> body
      json/encode
      rr/response
      (rr/status status)
      (rr/content-type "application/json; charset=utf-8")))

(defn plain-response [status body]
  (-> body
      rr/response
      (rr/status status)
      (rr/content-type "text/plain; charset=utf-8")))

(defn sanitize-client-cert
  "Given a ring request, return a map which replaces the :ssl-client-cert with
  just the certificate's Common Name at :ssl-client-cert-cn.  Also, remove the
  copy of the certificate put on the request by TK-auth."
  [req]
  (-> (if-let [client-cert (:ssl-client-cert req)]
        (-> req
            (dissoc :ssl-client-cert)
            (assoc :ssl-client-cert-cn (ssl-utils/get-cn-from-x509-certificate client-cert)))
        req)
      (ks/dissoc-in [:authorization :certificate])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Middleware

(defn wrap-request-logging
  "A ring middleware that logs the request."
  [handler]
  (fn [{:keys [request-method uri] :as req}]
    (log/debug "Processing" request-method uri)
    (log/trace (str "Full request:\n" (ks/pprint-to-string (sanitize-client-cert req))))
    (handler req)))

(defn wrap-response-logging
  "A ring middleware that logs the response."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (log/trace "Computed response:" resp)
      resp)))

(defn wrap-proxy
  "Proxies requests to proxied-path, a local URI, to the remote URI at
  remote-uri-base, also a string."
  [handler proxied-path remote-uri-base & [http-opts]]
  (let [proxied-path (if (instance? Pattern proxied-path)
                       (re-pattern (str "^" (.pattern proxied-path)))
                       proxied-path)]
       (cookies/wrap-cookies
         (fn [req]
             (if (or (and (string? proxied-path) (.startsWith ^String (:uri req) (str proxied-path "/")))
                     (and (instance? Pattern proxied-path) (re-find proxied-path (:uri req))))
               (common/proxy-request req proxied-path remote-uri-base http-opts)
               (handler req))))))

(defn wrap-add-cache-headers
  "Adds cache control invalidation headers to GET and PUT requests if they are handled by the handler"
  [handler]
  (fn [request]
    (let [request-method (:request-method request)
          response       (handler request)]
      (when-not (nil? response)
        (if (or
              (= request-method :get)
              (= request-method :put))
            (assoc-in response [:headers "cache-control"] "private, max-age=0, no-cache")
            response)))))

(defn wrap-add-x-frame-options-deny
  "Adds 'X-Frame-Options: DENY' headers to requests if they are handled by the handler"
  [handler]
  (fn [request]
    (let [response (handler request)]
      (when response
        (assoc-in response [:headers "X-Frame-Options"] "DENY")))))

(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [handler]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (some-> ssl-client-cert
                      ssl-utils/get-cn-from-x509-certificate)
          req (assoc req :ssl-client-cn cn)]
      (handler req))))

(schema/defn ^:always-validate wrap-data-errors
  "A ring middleware that catches slingshot errors of :type
  :request-dava-invalid and returns a 400."
  ([handler :- IFn]
   (wrap-data-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 400
         response (fn [e]
                    (log/error "Submitted data is invalid: "  (:message e))
                    (case type
                      :json (json-response code {:error e})
                      :plain (plain-response code (:message e))))]
     (fn [request]
       (try+ (handler request)
             (catch
               #(contains? #{:request-data-invalid
                             :user-data-invalid
                             :service-status-version-not-found}
                           (:type %))
               e
               (response e)))))))

(schema/defn ^:always-validate wrap-schema-errors
  "A ring middleware that catches schema errors and returns a 500
  response with the details"
  ([handler :- IFn]
   (wrap-schema-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Something unexpected happened: "
                                   (select-keys (.getData e) [:error :value :type]))]
                      (log/error msg)
                      (case type
                        :json (json-response code
                                             {:error
                                              {:type :application-error
                                               :message msg}})
                        :plain (plain-response code msg))))]
     (fn [request]
       (try (handler request)
            (catch clojure.lang.ExceptionInfo e
              (let [message (.getMessage e)]
                (if (re-find #"does not match schema" message)
                  (response e)
                  ;; re-throw exceptions that aren't schema errors
                  (throw e)))))))))

(schema/defn ^:always-validate wrap-uncaught-errors
  "A ring middleware that catches all otherwise uncaught errors and
  returns a 500 response with the error message"
  ([handler :- IFn]
   (wrap-uncaught-errors handler :json))
  ([handler :- IFn
    type :- ResponseType]
   (let [code 500
         response (fn [e]
                    (let [msg (str "Internal Server Error: " e)]
                      (log/error msg)
                      (case type
                        :json (json-response code
                                             {:error
                                              {:type :application-error
                                               :message msg}})
                        :plain (plain-response code msg))))]
     (fn [request]
       (try (handler request)
            (catch Exception e
              (response e)))))))

