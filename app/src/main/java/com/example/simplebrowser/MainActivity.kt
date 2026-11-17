package com.example.simplebrowser

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.graphics.Color
import android.view.ViewGroup

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progress: ProgressBar
    private var retryCount = 0
    private var hadError = false
    private lateinit var splash: View
    private lateinit var container: android.widget.FrameLayout
    private var customView: View? = null
    private var customFrame: android.widget.FrameLayout? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // 컨트롤이 사라지지 않게 터치 후 일정 시간 동안 가상 마우스 이동 이벤트를 보냄
    private var keepUntil = 0L
    private var keepRunning = false
    private var lastX = 0f
    private var lastY = 0f
    private var flip = false

    private val keepRunnable = object : Runnable {
        override fun run() {
            if (android.os.SystemClock.uptimeMillis() > keepUntil) {
                keepRunning = false
                return
            }
            val cv = customView
            if (cv != null) {
                sendHover(cv, cv.width / 2f, cv.height / 2f)
            } else if (::webView.isInitialized) {
                val js = "(function(){ if (window.__ctlPing) window.__ctlPing(); " +
                    "return window.__ctlRect ? window.__ctlRect() : ''; })()"
                webView.evaluateJavascript(js) { r ->
                    val v = r?.trim('"') ?: ""
                    val parts = v.split(",")
                    if (parts.size == 2) {
                        val cx = parts[0].toFloatOrNull()
                        val cy = parts[1].toFloatOrNull()
                        if (cx != null && cy != null) {
                            val d = resources.displayMetrics.density
                            sendHover(webView, cx * d, cy * d)
                        }
                    }
                }
            }
            container.postDelayed(this, 700)
        }
    }

    // 플레이어 중앙에 가상 마우스 이동(hover) 이벤트를 보내 컨트롤이 사라지는 것을 늦춤
    private fun sendHover(target: View, localX: Float, localY: Float) {
        flip = !flip
        val hx = localX + (if (flip) 1f else -1f)
        val hy = localY
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = hx
            y = hy
            pressure = 0f
            size = 1f
        })
        val t = android.os.SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0
        )
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_UP) {
            lastX = ev.rawX
            lastY = ev.rawY
            keepUntil = android.os.SystemClock.uptimeMillis() + CONTROLS_KEEP_MS
            if (!keepRunning && ::container.isInitialized) {
                keepRunning = true
                container.postDelayed(keepRunnable, 500)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyImmersive(on: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (on)
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = lp
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(android.view.WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                ) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = cb
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        customFrame = frame
        container.addView(frame, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyImmersive(true)
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        val frame = customFrame
        val cb = customCallback
        customView = null
        customFrame = null
        customCallback = null
        if (frame != null) {
            frame.removeView(v)
            container.removeView(frame)
        }
        cb?.onCustomViewHidden()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyImmersive(false)
        root0?.requestApplyInsets()
    }
    private var splashDone = false
    private var root0: LinearLayout? = null

    private fun hideSplash() {
        if (splashDone) return
        splashDone = true
        splash.animate().alpha(0f).setDuration(300).withEndAction {
            splash.visibility = View.GONE
        }.start()
    }

    companion object {
        private const val HOME_URL = "https://tvmon1.com/"
        // 터치 후 재생 컨트롤을 유지할 시간(밀리초). 늘리면 컨트롤이 더 오래 보입니다
        private const val CONTROLS_KEEP_MS = 2000L
        // true 로 바꾸면 맨 위 주소창 메뉴바가 다시 보입니다
        private const val SHOW_TOOLBAR = true


        // 사이트 상단 메뉴바(TVMON 로고 / 메뉴 / 로그인 줄) 숨김
        private const val HIDE_SITE_HEADER = true
        private val HEADER_JS = """
(function(){
  if (!__FLAG__) return;
  if (window.__hdr) { window.__hdrRun(); return; }
  window.__hdr = 1;
  function run(){
    var sel = 'header,#header,#hd,.header,[class*=header],[id*=header],nav,[class*=gnb],[id*=gnb],[class*=topbar],[class*=top-bar]';
    var hs = document.querySelectorAll(sel);
    for (var i = 0; i < hs.length; i++) {
      var h = hs[i];
      if (h.__hh) continue;
      var r = h.getBoundingClientRect();
      if (r.height < 30 || r.height > 220 || r.width < window.innerWidth * 0.8) continue;
      var cs = getComputedStyle(h);
      var docTop = (cs.position === 'fixed') ? r.top : r.top + window.scrollY;
      if (docTop > 5) continue;
      if (!/로그인|logo/i.test(h.textContent + h.innerHTML.slice(0, 3000))) continue;
      h.__hh = 1;
      var hh = r.height;
      var nx = h.nextElementSibling;
      h.style.setProperty('display', 'none', 'important');
      if (nx && !nx.textContent.trim() && !nx.querySelector('img,video,iframe')) {
        var nh = nx.getBoundingClientRect().height;
        if (Math.abs(nh - hh) <= 8) nx.style.setProperty('display', 'none', 'important');
      }
      var els = [document.documentElement, document.body].concat(Array.prototype.slice.call(document.body.children));
      for (var j = 0; j < els.length; j++) {
        var c = getComputedStyle(els[j]);
        var pt = parseFloat(c.paddingTop), mt = parseFloat(c.marginTop);
        if (pt >= hh - 8 && pt <= hh + 8) els[j].style.setProperty('padding-top', '0', 'important');
        if (mt >= hh - 8 && mt <= hh + 8) els[j].style.setProperty('margin-top', '0', 'important');
      }
    }
  }
  window.__hdrRun = run;
  run();
  new MutationObserver(run).observe(document.documentElement, {childList: true, subtree: true});
  var n = 0;
  var t = setInterval(function(){ run(); if (++n > 20) clearInterval(t); }, 500);
})();
""".replace("__FLAG__", HIDE_SITE_HEADER.toString())


        // 사이트 상단 빈 여백 제거
        private val GAP_JS = """
(function(){
  if (window.__gap) { window.__gapRun(); return; }
  window.__gap = 1;
  var LIMIT = 20, tries = 0;
  function fixedAncestor(e){
    for (var n = e; n && n !== document.documentElement; n = n.parentElement) {
      var p = getComputedStyle(n).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }
  function visible(e){
    var r = e.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var c = getComputedStyle(e);
    return c.display !== 'none' && c.visibility !== 'hidden' && c.opacity !== '0';
  }
  function hasOwnText(e){
    for (var k = e.firstChild; k; k = k.nextSibling)
      if (k.nodeType === 3 && k.nodeValue.trim()) return true;
    return false;
  }
  function firstContent(){
    var all = document.body.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      var tag = e.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') continue;
      var leaf = (tag === 'IMG' || tag === 'SVG' || tag === 'SELECT' || tag === 'INPUT' || tag === 'BUTTON' || tag === 'VIDEO' || tag === 'IFRAME' || tag === 'CANVAS');
      if (!leaf) {
        var cn = (typeof e.className === 'string' ? e.className : '') + ' ' + (e.id || '');
        if (/player|video/i.test(cn) && e.getBoundingClientRect().height > 100) leaf = true;
      }
      if (!leaf && !hasOwnText(e)) continue;
      if (!visible(e) || fixedAncestor(e)) continue;
      return e;
    }
    return null;
  }
  function topOf(e){ return e.getBoundingClientRect().top + window.scrollY; }
  function run(){
    if (!document.body || tries > LIMIT) return;
    var e = firstContent();
    if (!e) return;
    var top = topOf(e);
    if (top < 8 && window.__gw) {
      var cw = parseFloat(getComputedStyle(window.__gw).marginTop) || 0;
      window.__gw.style.setProperty('margin-top', (cw + (24 - top)) + 'px', 'important');
      return;
    }
    if (top <= 24) return;
    tries++;
    var chain = [];
    for (var n = e; n && n !== document.body; n = n.parentElement) chain.push(n);
    for (var i = 0; i < chain.length; i++) {
      var node = chain[i];
      if (i > 0) {
        var c = getComputedStyle(node);
        if (parseFloat(c.paddingTop) > 0.5) node.style.setProperty('padding-top', '0', 'important');
        if (parseFloat(c.marginTop) > 0.5) node.style.setProperty('margin-top', '0', 'important');
      }
      for (var pv = node.previousElementSibling; pv; pv = pv.previousElementSibling) {
        if (pv.tagName === 'SCRIPT' || pv.tagName === 'STYLE') continue;
        var r = pv.getBoundingClientRect();
        var cs = getComputedStyle(pv);
        if (cs.position === 'fixed' || cs.position === 'sticky' || cs.position === 'absolute') continue;
        if (r.height > 4 && !pv.textContent.trim() && !pv.querySelector('img,video,iframe,svg,input,select,canvas,[class*=player],[class*=video]')) {
          pv.style.setProperty('display', 'none', 'important');
        }
      }
    }
    var cb = getComputedStyle(document.body);
    if (parseFloat(cb.paddingTop) > 0.5) document.body.style.setProperty('padding-top', '0', 'important');
    if (parseFloat(cb.marginTop) > 0.5) document.body.style.setProperty('margin-top', '0', 'important');
    var left = topOf(e) - 24;
    if (left > 4) {
      var w = chain[chain.length - 1];
      window.__gw = w;
      var cur = parseFloat(getComputedStyle(w).marginTop) || 0;
      w.style.setProperty('margin-top', (cur - left) + 'px', 'important');
    }
  }
  window.__gapRun = run;
  run();
  document.addEventListener('load', run, true);
  new MutationObserver(run).observe(document.documentElement, {childList: true, subtree: true});
  var iv = 0;
  var t = setInterval(function(){ run(); if (tries > LIMIT || ++iv > 30) clearInterval(t); }, 500);
})();
"""


        // 하단 탭 메뉴(영화/드라마/애니...)가 내용에 가려지지 않게 맨 앞으로 고정
        private val NAV_JS = """
(function(){
  if (window.__nav) { window.__navRun(); return; }
  window.__nav = 1;
  var done = null;
  function isNav(e){
    var r = e.getBoundingClientRect();
    if (r.height < 40 || r.height > 220 || r.width < window.innerWidth * 0.9) return false;
    if (r.bottom < window.innerHeight - 3) return false;
    var tx = e.textContent || '';
    return tx.indexOf('영화') >= 0 && tx.indexOf('드라마') >= 0 && tx.indexOf('애니') >= 0;
  }
  function solid(e){
    var bg = getComputedStyle(e).backgroundColor;
    var m = bg.match(/rgba?\(([^)]+)\)/);
    if (!m) return false;
    var p = m[1].split(',');
    return p.length < 4 || parseFloat(p[3]) >= 0.99;
  }
  function run(){
    if (!document.body) return;
    if (!done) {
      var all = document.body.querySelectorAll('*');
      var best = null;
      for (var i = 0; i < all.length; i++) {
        var e = all[i];
        if (getComputedStyle(e).position !== 'fixed') continue;
        if (!isNav(e)) continue;
        best = e;
        break;
      }
      if (best) done = best;
    }
    if (!done) return;
    done.style.setProperty('z-index', '2147483647', 'important');
    if (!solid(done)) done.style.setProperty('background-color', '#111', 'important');
    var h = done.getBoundingClientRect().height;
    var cur = parseFloat(getComputedStyle(document.body).paddingBottom) || 0;
    if (cur < h) document.body.style.setProperty('padding-bottom', (h + 8) + 'px', 'important');
  }
  window.__navRun = run;
  run();
  document.addEventListener('load', run, true);
  new MutationObserver(run).observe(document.documentElement, {childList: true, subtree: true});
  var n = 0;
  var t = setInterval(function(){ run(); if (++n > 40) clearInterval(t); }, 500);
})();
"""


        // 사이트 하단 안내문(대피소/광고문의/저작권 문구) 숨김
        private val FOOTER_JS = """
(function(){
  if (window.__ftr) { window.__ftrRun(); return; }
  window.__ftr = 1;
  function hasNav(n){
    var t = n.textContent || '';
    return t.indexOf('드라마') >= 0 && t.indexOf('애니') >= 0;
  }
  function run(){
    if (!document.body) return;
    var all = document.body.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.__ft) continue;
      var tg = e.tagName;
      if (tg === 'SCRIPT' || tg === 'STYLE') continue;
      var own = '';
      for (var k = e.firstChild; k; k = k.nextSibling) if (k.nodeType === 3) own += k.nodeValue;
      if (own.indexOf('무료 다시보기') < 0 && own.indexOf('All right reserved') < 0 && own.indexOf('대피소') < 0) continue;
      var node = e;
      while (node.parentElement && node.parentElement !== document.body) {
        var p = node.parentElement;
        if (hasNav(p) || (p.textContent || '').length > 900 || p.querySelector('img')) break;
        node = p;
      }
      node.__ft = 1; e.__ft = 1;
      node.style.setProperty('display', 'none', 'important');
      var pv = node.previousElementSibling;
      if (pv && pv.tagName === 'HR') pv.style.setProperty('display', 'none', 'important');
    }
    var ft = document.querySelectorAll('footer,#footer,.footer');
    for (var j = 0; j < ft.length; j++) {
      var f = ft[j];
      var tx = f.textContent || '';
      if (!hasNav(f) && (tx.indexOf('Copyright') >= 0 || tx.indexOf('저작권') >= 0)) f.style.setProperty('display', 'none', 'important');
    }
  }
  window.__ftrRun = run;
  run();
  new MutationObserver(run).observe(document.documentElement, {childList: true, subtree: true});
  var n = 0;
  var t = setInterval(function(){ run(); if (++n > 40) clearInterval(t); }, 500);
})();
"""


        // 하단 메뉴에서 '뉴토끼' 항목 제거
        private val TAB_JS = """
(function(){
  if (window.__tab) { window.__tabRun(); return; }
  window.__tab = 1;
  function fixedAnc(e){
    for (var n = e; n && n !== document.documentElement; n = n.parentElement)
      if (getComputedStyle(n).position === 'fixed') return true;
    return false;
  }
  function run(){
    if (!document.body) return;
    var all = document.body.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      if (e.__tb) continue;
      var own = '';
      for (var k = e.firstChild; k; k = k.nextSibling) if (k.nodeType === 3) own += k.nodeValue;
      if (own.trim() !== '뉴토끼') continue;
      if (!fixedAnc(e)) continue;
      var node = e;
      while (node.parentElement && node.parentElement.children.length < 5) node = node.parentElement;
      var row = node.parentElement;
      if (!row || row === document.body) continue;
      e.__tb = 1;
      node.style.setProperty('display', 'none', 'important');
      var vis = 0;
      for (var j = 0; j < row.children.length; j++)
        if (getComputedStyle(row.children[j]).display !== 'none') vis++;
      if (getComputedStyle(row).display === 'grid')
        row.style.setProperty('grid-template-columns', 'repeat(' + vis + ', 1fr)', 'important');
    }
  }
  window.__tabRun = run;
  run();
  new MutationObserver(run).observe(document.documentElement, {childList: true, subtree: true});
  var n = 0;
  var t = setInterval(function(){ run(); if (++n > 40) clearInterval(t); }, 500);
})();
"""


        // 재생 컨트롤 유지를 위한 가상 마우스 이동 (접근 가능한 모든 문서의 video 대상)
        private val CONTROL_JS = """
(function(){
  if (window.__ctlPing) return;
  var flip = false;
  function collect(doc, list, depth){
    list.push(doc);
    if (depth > 3) return;
    var fr = doc.querySelectorAll('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { var d = fr[i].contentDocument; if (d) collect(d, list, depth + 1); } catch (e) {}
    }
  }
  window.__ctlRect = function(){
    var best = null, ba = 0;
    var els = document.querySelectorAll('video,iframe');
    for (var i = 0; i < els.length; i++) {
      var r = els[i].getBoundingClientRect();
      if (r.width < 200 || r.height < 100) continue;
      if (r.bottom <= 0 || r.top >= window.innerHeight) continue;
      var a = r.width * r.height;
      if (a > ba) { ba = a; best = r; }
    }
    return best ? ((best.left + best.width / 2) + ',' + (best.top + best.height / 2)) : '';
  };
  window.__ctlPing = function(){
    var list = [];
    collect(document, list, 0);
    for (var a = 0; a < list.length; a++) {
      var d = list[a];
      var vs = d.querySelectorAll('video');
      for (var b = 0; b < vs.length; b++) {
        var v = vs[b];
        var r = v.getBoundingClientRect();
        if (r.width < 50) continue;
        flip = !flip;
        var x = r.left + r.width / 2 + (flip ? 1 : -1), y = r.top + r.height / 2;
        var el = v;
        for (var i = 0; i < 6 && el; i++) {
          try { el.dispatchEvent(new MouseEvent('mousemove', {bubbles: true, cancelable: true, clientX: x, clientY: y, view: d.defaultView})); } catch (e) {}
          el = el.parentElement;
        }
      }
    }
  };
})();
"""

        // 배너 광고 제거용 스크립트: 외부 사이트로 가는 이미지 링크, 가로로 긴 배너 이미지를 숨김
        private val AD_BLOCK_JS = """
(function(){
  if (window.__adb) { window.__adbScan(); return; }
  window.__adb = 1;
  function root(h){ return h.split('.').slice(-2).join('.'); }
  var base = root(location.hostname);
  function hide(el){
    var t = el;
    for (var i = 0; i < 3; i++) {
      var p = t.parentElement;
      if (!p || p === document.body || p.children.length !== 1) break;
      t = p;
    }
    t.style.setProperty('display', 'none', 'important');
  }
  function isExt(a){
    try {
      var u = new URL(a.href, location.href);
      return /^https?:$/.test(u.protocol) && root(u.hostname) !== base;
    } catch (e) { return false; }
  }
  function isSafe(a, img){
    if (a.closest('header,nav,footer,[id*=logo],[class*=logo],[id*=header],[class*=header]')) return true;
    var m = ((img.alt || '') + (img.className || '') + (img.id || '')).toLowerCase();
    if (m.indexOf('logo') >= 0) return true;
    try {
      var u = new URL(a.href, location.href);
      if (u.origin === location.origin && (u.pathname === '/' || u.pathname === '')) return true;
    } catch (e) {}
    return false;
  }
  function check(a){
    if (a.__hid) return;
    var img = a.querySelector('img');
    if (!img || isSafe(a, img)) return;
    if (isExt(a)) { a.__hid = 1; hide(a); return; }
    var w = img.naturalWidth, h = img.naturalHeight;
    if (img.complete && w > 2 && h > 2) {
      if (w / h >= 2.5 && w >= 250) { a.__hid = 1; hide(a); }
      else a.style.removeProperty('visibility');
    } else {
      a.style.setProperty('visibility', 'hidden', 'important');
    }
  }
  function scan(){
    var links = document.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) check(links[i]);
  }
  window.__adbScan = scan;
  scan();
  document.addEventListener('load', scan, true);
  document.addEventListener('error', scan, true);
  new MutationObserver(scan).observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['src','data-src']});
  var n = 0;
  var timer = setInterval(function(){ scan(); if (++n > 40) clearInterval(timer); }, 500);
})();
"""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 17, 17))
        }

        root0 = root
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setBackgroundColor(Color.rgb(17, 17, 17))
        }

        val backButton = Button(this).apply {
            text = "‹"
            setBackgroundColor(Color.rgb(50, 50, 50))
            setTextColor(Color.WHITE)
            textSize = 25f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            minWidth = dp(44)
            setOnClickListener {
                if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
            }
        }

        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "주소 입력"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.GRAY)
            setPadding(dp(10), 0, dp(10), 0)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigateFromAddress()
                    true
                } else false
            }
        }

        val goButton = Button(this).apply {
            text = "이동"
            setBackgroundColor(Color.rgb(50, 50, 50))
            setTextColor(Color.WHITE)
            isAllCaps = false
            setOnClickListener { navigateFromAddress() }
        }

        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        toolbar.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(goButton, LinearLayout.LayoutParams(dp(64), dp(48)))
        root.addView(toolbar)
        if (!SHOW_TOOLBAR) toolbar.visibility = View.GONE

        // 상태바/내비게이션바 영역 침범 방지 (Android 15 강제 edge-to-edge 대응)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        val toolbarPadTop = toolbar.paddingTop
        root.setOnApplyWindowInsetsListener { v, insets ->
            val l: Int; val t: Int; val r: Int; val b: Int
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.ime()
                )
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop
                    r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
                }
            }
            toolbar.setPadding(dp(8), toolbarPadTop + t, dp(8), dp(4))
            v.setPadding(l, if (SHOW_TOOLBAR) 0 else t, r, b)
            insets
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
        ))

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 17))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    // 메인 페이지 로드 실패(ERR_CONNECTION_RESET 등) 시 자동 재시도
                    if (request.isForMainFrame) {
                        hadError = true
                        if (retryCount < 6) {
                            retryCount++
                            val target = request.url.toString()
                            view.postDelayed({ view.loadUrl(target) }, 700L * retryCount)
                        } else {
                            hideSplash()
                        }
                    }
                }

                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    addressBar.setText(url)
                    view.evaluateJavascript(AD_BLOCK_JS + HEADER_JS + GAP_JS + NAV_JS + FOOTER_JS + TAB_JS + CONTROL_JS, null)
                    this@MainActivity.progress.visibility = View.VISIBLE
                    this@MainActivity.progress.progress = 5
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (hadError) {
                        hadError = false
                    } else {
                        retryCount = 0
                        view.postDelayed({ hideSplash() }, 600)
                    }
                    view.evaluateJavascript(AD_BLOCK_JS + HEADER_JS + GAP_JS + NAV_JS + FOOTER_JS + TAB_JS + CONTROL_JS, null)
                    addressBar.setText(url)
                    this@MainActivity.progress.progress = 100
                    this@MainActivity.progress.visibility = View.GONE
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                    showFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    view.evaluateJavascript(AD_BLOCK_JS + HEADER_JS + GAP_JS + NAV_JS + FOOTER_JS + TAB_JS + CONTROL_JS, null)
                    this@MainActivity.progress.progress = newProgress
                    if (newProgress >= 100) {
                        this@MainActivity.progress.visibility = View.GONE
                    } else {
                        this@MainActivity.progress.visibility = View.VISIBLE
                    }
                }
            }
        }

        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(14, 20, 36))
            isClickable = true
            val logo = android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
            }
            addView(logo, LinearLayout.LayoutParams(dp(112), dp(112)))
            val title = android.widget.TextView(this@MainActivity).apply {
                text = "티비몬"
                setTextColor(Color.rgb(0, 200, 255))
                textSize = 28f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            }
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val bar = ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 200, 255))
            }
            addView(bar, LinearLayout.LayoutParams(dp(36), dp(36)))
        }

        container = android.widget.FrameLayout(this)
        container.addView(root, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(splash, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(container)
        // 최대 20초 후에는 스플래시를 무조건 닫음
        container.postDelayed({ hideSplash() }, 20000)
        root.requestApplyInsets()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    private fun navigateFromAddress() {
        val input = addressBar.text.toString().trim()
        if (input.isEmpty()) return

        val url = when {
            input.startsWith("https://", ignoreCase = true) ||
                input.startsWith("http://", ignoreCase = true) -> input
            input.contains(" ") -> "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(input, "UTF-8")
            else -> "https://$input"
        }

        webView.loadUrl(url)
        addressBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) {
            exitFullscreen()
            return
        }
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
