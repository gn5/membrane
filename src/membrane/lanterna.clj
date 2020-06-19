(ns membrane.lanterna
  (:require [membrane.ui :as ui
             :refer [IBounds
                     bounds
                     -bounds
                     IOrigin
                     -origin
                     vertical-layout
                     horizontal-layout
                     maybe-key-press
                     defcomponent
                     on]]
            [membrane.skia :as skia]
            [clojure.core.async :as async
             :refer [<!! >!!]]
            [com.rpl.specter :as spec]
            ;; need effects
            [membrane.basic-components :as basic]
            [membrane.component :as component
             :refer [defui run-ui run-ui-sync defeffect]])
  
  (:import


   ;; import com.googlecode.lanterna.*;
   com.googlecode.lanterna.terminal.MouseCaptureMode
   com.googlecode.lanterna.input.MouseActionType
   com.googlecode.lanterna.terminal.ansi.UnixTerminal
   com.googlecode.lanterna.graphics.TextGraphics
   com.googlecode.lanterna.input.KeyStroke
   com.googlecode.lanterna.input.KeyType
   com.googlecode.lanterna.screen.Screen
   com.googlecode.lanterna.screen.TerminalScreen
   com.googlecode.lanterna.TerminalPosition
   com.googlecode.lanterna.terminal.DefaultTerminalFactory
   com.googlecode.lanterna.terminal.Terminal
   com.googlecode.lanterna.TextColor
   com.googlecode.lanterna.TextColor$ANSI
   com.googlecode.lanterna.TextColor$RGB
   com.googlecode.lanterna.TextColor$Indexed

   java.nio.charset.Charset)
  (:gen-class))

(defonce log-lines (atom []))
(defn log [s]
  (swap! log-lines (fn [lines]
                     (let [lines (conj lines (str s))
                           c (count lines)]
                       (subvec lines (max 0 (- c 30)))))))
(defn log-ui []
  (vec
   (apply
    vertical-layout
    (for [line @log-lines]
      (ui/label (subs line 0 (min 80 (count line))))))))

(defn run-log []
  (skia/run #'log-ui))


;; https://en.wikipedia.org/wiki/Block_Elements
;; https://en.wikipedia.org/wiki/Box-drawing_character

(def ^:dynamic *context* {})
(def ^:dynamic *tg* nil)
(def ^:dynamic *screen* nil)

(defprotocol IDraw
  (draw [this]))

(defn tp [col row]
  (TerminalPosition. col row))

(ui/add-default-draw-impls! IDraw #'draw)

(defcomponent Label [lines]
    IBounds
    (-bounds [this]
        [(apply max (map #(.length %) lines))
         (count lines)])
  IDraw
  (draw [this]
      (let [{:keys [x y]} (:translate *context*)]
        (doseq [[i line] (map-indexed vector lines)]
          (.putString *tg* x (+ y i) line))))
    IOrigin
    (-origin [_]
        [0 0]))


(defn -label
  "Graphical elem that can draw text.

  label will use the default line spacing for newline."
  [text]
  (Label. (clojure.string/split (str text) #"\n")))
(def label (memoize -label))


(defcomponent Rectangle [width height]
    IOrigin
    (-origin [_]
        [0 0])
    IBounds
    (-bounds [this]
        [width height])

    IDraw
    (draw [this]
        (let [{:keys [width height]} this
              {:keys [x y]} (:translate *context*)
          
              dx (dec width)
              dy (dec height)]

          (cond

            (and (<= width 1)
                 (<= height 1))
            (.setCharacter *tg*  (tp x y) \☐)

            :else
            (do
          
              (when (pos? (- height 2))
                ;; left edge
                (.drawLine *tg*
                           (tp x (inc y))
                           (tp x (dec (+ y dy)))
                           \│)
                ;; right edge
                (.drawLine *tg*
                           (tp (+ x dx) (inc y))
                           (tp (+ x dx) (dec (+ y dy)))
                           \│))

              (when (pos? (- width 2))
                ;; top edge
                (.drawLine *tg*
                           (tp (inc x) y)
                           (tp (dec (+ x dx)) y)
                           \─)
                ;; bottom edge
                (.drawLine *tg*
                           (tp (inc x) (+ y dy))
                           (tp (dec (+ x dx)) (+ y dy))
                           \─))

              ;; top left corner
              (.setCharacter *tg*
                             (tp x y)
                             \┌)
              ;; bottom left corner
              (.setCharacter *tg*
                             (tp x (+ y dy))
                             \└)
              ;; top right corner
              (.setCharacter *tg*
                             (tp (+ x dx)  y)
                             \┐)
              ;; bottom right corner
              (.setCharacter *tg*
                             (tp (+ x dx)  (+ y dy))
                             \┘))))))

(defn rectangle [width height]
  (Rectangle. width height))

(ui/defcomponent Button [text on-click hover?]
    ui/IOrigin
    (-origin [_]
        [0 0])

    IBounds
    (-bounds [this]
        [(+ 2 (count (:text this))) 3])

    ui/IMouseDown
    (-mouse-down [this [mx my]]
        (when on-click
          (on-click)))

    IDraw
    (draw [this]
        (let [text (:text this)]
          (draw
           [(rectangle (+ 2 (count text)) 3)
            (ui/translate 1 1
                          (label text))]))))

(defn button
  "Graphical elem that draws a button. Optional on-click function may be provided that is called with no arguments when button has a mouse-down event."
  ([text]
   (Button. text nil false))
  ([text on-click]
   (Button. text on-click false))
  ([text on-click hover?]
   (Button. text on-click hover?)))

;; TODO: change mx -> pos and support
;;       multi-line textareas
(defeffect ::move-cursor-to-pos [$cursor text mx]
  (run! #(apply dispatch! %)
        [[:update $cursor (fn [cursor]
                            (min (count text)
                                 mx))]]))

(defeffect ::finish-drag [$select-cursor $cursor $down-pos pos text]
  (let [[mx my] pos
        end-index (min (count text)
                       mx)]
    (run! #(apply dispatch! %)
          [
           [:update [(spec/collect-one (component/path->spec $down-pos))
                     $select-cursor]
            (fn [down-pos select-cursor]
              (when-let [[dx dy] down-pos]
                (let [idx (min (count text)
                               dx)]
                  (when (not= idx end-index)
                    (if (> idx end-index)
                      (min (count text) (inc idx))
                      idx))))
              
              )]
           [:set $down-pos nil]
           [:update [(spec/collect-one (component/path->spec $select-cursor))
                     $cursor]
            (fn [select-cursor cursor]
              (if (and select-cursor (> end-index select-cursor))
                (min (count text) (inc end-index))
                end-index))]])))

(def double-click-threshold 500)
(let [getTimeMillis (fn [] (.getTime ^java.util.Date (java.util.Date.)))
      pow (fn [n x] (Math/pow n x))
      
      find-white-space (fn [text start]
                         (let [matcher (doto (re-matcher  #"\s" text)
                                         (.region start (count text)))]
                           (when (.find matcher)
                             (.start matcher))))]
  (defeffect ::text-double-click [$last-click $select-cursor $cursor pos text]
    (let [now (getTimeMillis)
          [mx my] pos]
      (run! #(apply dispatch! %)
            [
             [:update [(spec/collect-one (component/path->spec $last-click))
                       $select-cursor]
              (fn [[last-click [dx dy]] select-cursor]
                (if last-click
                  (let [diff (- now last-click)]
                    (if (and (< diff double-click-threshold)
                             (< (+ (pow (- mx dx) 2)
                                   (pow (- my dy) 2))
                                100))
                      (let [index (min (count text)
                                       mx)]
                        (if-let [start (find-white-space text index)]
                          start
                          (count text)))
                      select-cursor))
                  select-cursor))]
             [:update [(spec/collect-one (component/path->spec $last-click))
                       $cursor]
              (fn [[last-click [dx dy]] cursor]
                (if last-click
                  (let [diff (- now last-click)]
                    (if (and (< diff double-click-threshold)
                             (< (+ (pow (- mx dx) 2)
                                   (pow (- my dy) 2))
                                100))
                      (let [index (min (count text)
                                       mx)
                            text-backwards (clojure.string/reverse text)]
                        (if-let [start (find-white-space text-backwards
                                                         (- (count text) index))]
                          (- (count text) start)
                          0)
                        )
                      cursor))
                  cursor))]

             [:set $last-click [now pos]]]))
    ))

(defcomponent CheckboxView [checked?]
    IOrigin
    (-origin [_]
        [0 0])

    IDraw
    (draw [this]
        ;; looks like:
        ;; [ ] unchecked
        ;; [*] checked
        ;; tried using ☐ and ☑, but I think this looks better
        (let [{:keys [x y]} (:translate *context*)
              ]
          (.setCharacter *tg*  (tp x y) \[)
          (.setCharacter *tg*  (tp (+ 2 x) y) \])

          (if checked?
            (let [{:keys [x y]} (:translate *context*)]
              (.setCharacter *tg*  (tp (inc x) y) \* )))))

    IBounds
    (-bounds [this]
        [3 1]))

(defn checkbox-view
  "Graphical elem that will draw a checkbox."
  [checked?]
  (CheckboxView. checked?))

(defui checkbox
  "Checkbox component."
  [& {:keys [checked?]}]
  (on
   :mouse-down
   (fn [_]
     [[:update $checked? not]])
   (checkbox-view checked?)))


(defcomponent TextSelection [text selection]
    
    IBounds
    (-bounds [this]
        (bounds (label text)))
  IDraw
  (draw [this]
      (let [old-bg (.getBackgroundColor *tg*)
            {:keys [x y]} (:translate *context*)
            
            text (:text this)
            text-length (count text)
            [start end] selection]
        (.setBackgroundColor *tg*
                             (TextColor$Indexed/fromRGB 185
                                                        215
                                                        251))
        (doseq [cur (range start end)
                :let [c (.charAt text cur)]]
          (.setCharacter *tg* (tp (+ x cur) y) c))
        (.setBackgroundColor *tg* old-bg)
        )
      )
    IOrigin
    (-origin [_]
        [0 0]))

(defn text-selection
  "Graphical elem for drawing a selection of text."
  ([text [selection-start selection-end :as selection]]
   (TextSelection. (str text) selection )))



;; TODO: add support for text selection
;;       text selection is currently annoying because of the
;;       way foreground and background work.
(defui textarea-view
  "Raw component for a basic textarea. textarea should be preferred."
  [& {:keys [cursor
             focus?
             text
             down-pos
             mpos
             select-cursor
             last-click]
      :or {cursor 0
           text ""}}]
  (let [text (or text "")]
    (maybe-key-press
     focus?
     (on
      :key-press
      (fn [s]
        (when focus?
          (case s

            :up
            [[:membrane.basic-components/previous-line $cursor $select-cursor  text]]

            :enter
            [[:membrane.basic-components/insert-newline $cursor $select-cursor $text]]

            :down
            [[:membrane.basic-components/next-line $cursor $select-cursor text]]

            :left
            [[:membrane.basic-components/backward-char $cursor $select-cursor text]]

            :right
            [[:membrane.basic-components/forward-char $cursor $select-cursor text]]

            :backspace
            [[:membrane.basic-components/delete-backward $cursor $select-cursor $text]]

            ;; else
            (when (string? s)
              [[:membrane.basic-components/insert-text  $cursor $select-cursor $text s]]))))
      :mouse-up
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         [[::finish-drag $select-cursor $cursor $down-pos pos text]
          [::text-double-click $last-click $select-cursor $cursor pos text]
          ]))
      :mouse-down
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         [[::request-focus]
          [::move-cursor-to-pos $cursor text mx]
          [:membrane.basic-components/start-drag $mpos $down-pos pos]
          [:set $select-cursor nil]]))
      :mouse-move
      (fn [[mx my :as pos]]
        (let [[mx my :as pos] (mapv (comp (partial max 0) dec) pos)]
         (when down-pos
           [[:membrane.basic-components/drag $mpos pos]])))
      
      (let [lbl (label text)
            [label-width label-height] (bounds lbl)]
        [(ui/with-color [0.5 0.5 0.5]
           (ui/rounded-rectangle (+ 3 (count text)) (+ 2 label-height) 0))
         
         (ui/translate 1 1
                       [lbl
                        (when select-cursor
                          (text-selection text
                                          [(min select-cursor cursor)
                                           (max select-cursor cursor)]))
                        (when (and mpos down-pos)
                          (let [mx (first mpos)
                                dx (first down-pos)]
                           (text-selection text
                                           [(max 0 (min mx dx))
                                            (min (count text) (max mx dx))])))
                        (when focus?
                          (ui/text-cursor text cursor nil))])
         ])
      ))))

(defui textarea
  "Textarea component."
  [& {:keys [text
             ^:membrane.component/contextual focus
             textarea-state]}]
  (on
   ::request-focus
   (fn []
     [[:set [$focus] $text]])
   (textarea-view :text text
                  :cursor (get textarea-state :cursor 0)
                  :focus? (= focus $text)
                  :down-pos (:down-pos textarea-state)
                  :mpos (:mpos textarea-state)
                  :select-cursor (:select-cursor textarea-state))))

;; TODO: add some basic image support
#_(extend-type membrane.ui.Image
    IBounds
    (-bounds [this]
      (:size this))
    IDraw
    (draw [this]
      (when-let [image-info (get @images (:image-path this))]
        (let [[width height] (:size this)]
          (push-state *ctx*
                      (when-let [opacity (:opacity this)]
                        (set! (.-globalAlpha *ctx*) opacity))
                      (.drawImage *ctx*
                                  (:image-obj image-info)
                                  0 0
                                  width height))))))


(extend-type membrane.ui.Translate
  IDraw
  (draw [this]
    (binding [*context* (->> *context*
                             (spec/transform [:translate :x] (partial + (:x this)))
                             (spec/transform [:translate :y] (partial + (:y this)))) ]
      (draw (:drawable this)))))

;; (extend-type membrane.ui.TextSelection
;;   IBounds
;;   (-bounds [this]
;;     (text-bounds (:font this) (:text this)))

;;   IDraw
;;   (draw [this]
;;     (let [{:keys [text font]
;;            [selection-start selection-end] :selection} this]
;;       (render-selection (:font this) text selection-start selection-end
;;                         [0.6980392156862745
;;                          0.8431372549019608
;;                          1]))))


(extend-type membrane.ui.TextCursor
  IBounds
  (-bounds [this]
    [(count (:text this)) 1])

  IDraw
  (draw [this]
    (let [old-bg (.getBackgroundColor *tg*)
          {:keys [x y]} (:translate *context*)
          cur (:cursor this)
          row y
          text (:text this)
          text-length (count text)
          col (+ x (min cur
                        text-length))
          pos (TerminalPosition. col row)
          c (if (>= cur text-length)
              \space
              (.charAt text cur))]
      
      ;; (.setCursorPosition *screen* (TerminalPosition. col row))
      (.setBackgroundColor *tg*
                           ;; TextColor$ANSI/RED
                           (TextColor$Indexed/fromRGB 146
                                                        146
                                                        146))
      
      (.setCharacter *tg* pos c)
      (.setBackgroundColor *tg* old-bg)
      )))


(extend-type membrane.ui.RoundedRectangle

  IDraw
  (draw [this]
    (let [{:keys [width height border-radius]} this
          {:keys [x y]} (:translate *context*)
          
          dx (dec width)
          dy (dec height)
          ]

      

      (cond

        (and (<= width 1)
             (<= height 1))
        (.setCharacter *tg*  x y \O)

        :else
        (do
          
          (when (pos? (- height 2))
            ;; left edge
            (.drawLine *tg*
                       (tp x (inc y))
                       (tp x (dec (+ y dy)))
                       \│)
            ;; right edge
            (.drawLine *tg*
                       (tp (+ x dx) (inc y))
                       (tp (+ x dx) (dec (+ y dy)))
                       \│))

          (when (pos? (- width 2))
            ;; top edge
            (.drawLine *tg*
                       (tp (inc x) y)
                       (tp (dec (+ x dx)) y)
                       \─)
            ;; bottom edge
            (.drawLine *tg*
                       (tp (inc x) (+ y dy))
                       (tp (dec (+ x dx)) (+ y dy))
                       \─))

          ;; top left corner
          (.setCharacter *tg*
                         (tp x y)
                         \╭)
          ;; bottom left corner
          (.setCharacter *tg*
                         (tp x (+ y dy))
                         \╰)
          ;; top right corner
          (.setCharacter *tg*
                         (tp (+ x dx)  y)
                         \╮)
          ;; bottom right corner
          (.setCharacter *tg*
                         (tp (+ x dx)  (+ y dy))
                         \╯)))

      #_(.putString *tg*  1 1 (pr-str *context*)))))

;; (extend-type membrane.ui.Path
;;   IDraw
;;   (draw [this]
;;     (push-state *ctx*
;;                 (.beginPath *ctx*)
;;                 (let [[x y] (first (:points this))]
;;                   (.moveTo *ctx* x y))
;;                 (doseq [[x y] (rest (:points this))]
;;                   (.lineTo *ctx* x y))
;;                 (case *paint-style*
;;                   :membrane.ui/style-fill (.fill *ctx*)
;;                   :membrane.ui/style-stroke (.stroke *ctx*)
;;                   :membrane.ui/style-stroke-and-fill (doto *ctx*
;;                                                        (.stroke)
;;                                                        (.fill))))))

(extend-type membrane.ui.WithColor
  IDraw
  (draw [this]
    (let [[r g b] (:color this)
          old-fg (.getForegroundColor *tg*)]
      (.setForegroundColor *tg* (TextColor$Indexed/fromRGB (int (Math/round (* 255.0 r)))
                                                           (int (Math/round (* 255.0 g)))
                                                           (int (Math/round (* 255.0 b))) ))
      (doseq [drawable (:drawables this)]
        (draw drawable))
      (.setForegroundColor *tg* old-fg))))



#_(extend-type membrane.ui.Image
  IBounds
  (-bounds [this]
    (:size this))
  IDraw
  (draw [this]
    (when-let [image-info (get @images (:image-path this))]
      (let [[width height] (:size this)]
        (push-state *ctx*
                    (when-let [opacity (:opacity this)]
                      (set! (.-globalAlpha *ctx*) opacity))
                    (.drawImage *ctx*
                                (:image-obj image-info)
                                0 0
                                width height))))))


(defn run-helper [make-ui repaint-ch close-ch handler]
  (let [
        term (doto (UnixTerminal. System/in System/out (Charset/defaultCharset))
               (.setMouseCaptureMode MouseCaptureMode/CLICK_RELEASE_DRAG_MOVE))
        screen (TerminalScreen. term)
        input-future (future
                       (try
                         (loop [ui (make-ui)
                                last-ui nil]
                           (when (not= ui last-ui) 
                             (>!! repaint-ch ui))
                           (handler ui (.readInput screen))
                           (recur (make-ui) ui))
                         (catch Exception e
                           (log e))
                         (finally
                           (log "closing input")
                           (async/close! close-ch))))]

    (.setCursorPosition screen nil)
    (.startScreen screen)
    (log "starting")
    (let [tg (.newTextGraphics screen)]
      (try
        (doto tg
          (.setForegroundColor TextColor$ANSI/BLACK)
          (.setBackgroundColor TextColor$ANSI/DEFAULT))

        (>!! repaint-ch (make-ui))
        (loop []
          (let [[ui port] (async/alts!! [close-ch repaint-ch]
                                         :priority true)]
            (when (= port repaint-ch)
              (binding [*tg* tg
                        *context* {:translate {:x 0 :y 0}}
                        *screen* screen]
                ;; (log "repainting")
                (.clear screen)
                (.setCursorPosition screen nil)
                (draw ui)
                
                (.refresh screen))
              (recur))))
        (catch Exception e
          (log e)
          (throw e))
        (finally
          (log "stopping repaint")
          (.close screen)
          (future-cancel input-future))))))

(defn run-sync
  ([make-ui]
   (run-sync make-ui nil))
  ([make-ui {:keys [handler repaint-ch close-ch] :as options}]
   (let [options
         (or options
             (let [repaint-ch (async/chan (async/sliding-buffer 1))
                   close-ch (async/promise-chan)]
               {:handler (fn [ui event]
                           (log event)
                           ;; (log (.getKeyType event))
                           (condp = (.getKeyType event)


                             KeyType/MouseEvent
                             ;; mouse
                             (do
                               (let [pos (.getPosition event)]
                                 (log (pr-str [:mx [(.getColumn pos)
                                                    (.getRow pos)]])))
                               (condp = (.getActionType event)
                                 
                                 MouseActionType/CLICK_DOWN
                                 (let [pos (.getPosition event)]
                                   (ui/mouse-down ui [(.getColumn pos)
                                                      (.getRow pos)]))

                                 MouseActionType/CLICK_RELEASE
                                 (let [pos (.getPosition event)]
                                   (ui/mouse-up ui [(.getColumn pos)
                                                      (.getRow pos)]))
                                 MouseActionType/DRAG
                                 (let [pos (.getPosition event)]
                                   (ui/mouse-move ui [(.getColumn pos)
                                                        (.getRow pos)]))
                                 MouseActionType/MOVE
                                 (let [pos (.getPosition event)]
                                   (ui/mouse-move ui [(.getColumn pos)
                                                        (.getRow pos)]))
                                 MouseActionType/SCROLL_DOWN nil
                                 MouseActionType/SCROLL_UP nil))

                             KeyType/Character
                             (do
                               ;; (ui/key-event ui key scancode action mods)
                               (when-let [c (.getCharacter event)]
                                 (ui/key-press ui (str c))))

                             KeyType/Backspace
                             (ui/key-press ui :backspace)

                             KeyType/Enter
                             (ui/key-press ui :enter)

                             KeyType/ArrowDown
                             (ui/key-press ui :down)
                             KeyType/ArrowLeft
                             (ui/key-press ui :left)
                             KeyType/ArrowRight
                             (ui/key-press ui :right)
                             KeyType/ArrowUp
                             (ui/key-press ui :up)))
                :repaint-ch repaint-ch
                :close-ch close-ch}))]
     (run-helper make-ui
                 (:repaint-ch options)
                 (:close-ch options)
                 (:handler options)))))



(defn run
  ([make-ui options]
   (async/thread
     (run-sync make-ui options)))
  ([make-ui]
   (async/thread
     (run-sync make-ui))))


(defui term-test [ & {:keys [num s]}]
  (vertical-layout
   (label (str "count: " num))
   (textarea :text s)
   (button "more" (fn []
                    [[:update $num inc]]))))



;;; todo app

(defui todo-item [ & {:keys [todo]}]
  (horizontal-layout
   (on
    :mouse-down
    (fn [[mx my]]
      (log (str "delete mx: " (pr-str [mx my])))
      [[:delete $todo]])
    (ui/with-color [1 0 0]
      (label "X")))
   (checkbox :checked? (:complete? todo))
   (ui/wrap-on
    :key-press
    (fn [default-handler s]
      (when (not= s :enter)
        (default-handler s)))
    (textarea :text (:description todo)))))

(comment
  (run-ui #'todo-item {:todo
                       {:complete? false
                        :description "fix me"}}))


;; Display a list of `todo-item`s stacked vertically
;; Add 5px of spacing between `todo-item`s
(defui todo-list [ & {:keys [todos]}]
  (apply
   vertical-layout
   (for [todo todos]
     (todo-item :todo todo))))

(comment
  (run-ui #'todo-list {:todos
                       [{:complete? false
                         :description "first"}
                        {:complete? false
                         :description "second"}
                        {:complete? true
                         :description "third"}]}))



(def filter-fns
  {:all (constantly true)
   :active (comp not :complete?)
   :complete? :complete?})

;; Create a toggle that allows the user
;; to toggle between options
(defui toggle [& {:keys [options selected]}]
  (apply
   horizontal-layout
   (for [option options]
     (if (= option selected)
       (label (name option))
       (on
        :mouse-down
        (fn [[mx my]]
          [[:set $selected option]])
        (ui/with-color [0.8 0.8 0.8]
          (label (name option))))))))

(comment
  (run-ui #'toggle
          {:options [:all :active :complete?]
           :selected nil}))

(defui todo-app [ & {:keys [todos next-todo-text selected-filter]
                     :or {selected-filter :all}}]
  (vertical-layout
   (horizontal-layout
    (button "Add Todo"
            (fn []
              [[::add-todo $todos next-todo-text]
               [:set $next-todo-text ""]]))
    (ui/wrap-on
     :key-press
     (fn [default-handler s]
       (let [effects (default-handler s)]
         (if (and (seq effects)
                  (= s :enter))
           [[::add-todo $todos next-todo-text]
            [:set $next-todo-text ""]]
           effects)))
     (textarea :text next-todo-text)))
   (toggle :selected selected-filter :options [:all :active :complete?])
   (let [filter-fn (get filter-fns selected-filter :all)
         visible-todos (filter filter-fn todos)]
     (todo-list :todos visible-todos))))


(def todo-state (atom {:todos
                       [{:complete? false
                         :description "first"}
                        {:complete? false
                         :description "second"}
                        {:complete? true
                         :description "third"}]
                       :next-todo-text ""}))

(defeffect ::add-todo [$todos next-todo-text]
  (dispatch! :update $todos #(conj % {:description next-todo-text
                                      :complete? false})))

(comment
  (run-ui #'todo-app todo-state
                        ))

(comment
  (def todo-state
    (run-ui #'todo-app {:todos
                        [{:complete? false
                          :description "first"}
                         {:complete? false
                          :description "second"}
                         {:complete? true
                          :description "third"}]
                        :next-todo-text ""})))




(def todo-state (atom {:todos
                       [{:complete? false
                         :description "first"}
                        {:complete? false
                         :description "second"}
                        {:complete? true
                         :description "third"}]
                       :next-todo-text ""}))

(defn -main [& args]
  (run-log)
  (intern (the-ns 'membrane.ui) 'run run)
  (intern (the-ns 'membrane.ui) 'run-sync run-sync)

  

  ;; (component/run-ui-sync #'term-test {:num 0 :s "hh"})
  (component/run-ui-sync #'todo-app
                         todo-state
                         (let [default (component/default-handler todo-state)]
                           (fn [& effect]
                             (log (pr-str effect))
                             (apply default effect))))
  (.close System/in)
  (shutdown-agents)
  )
