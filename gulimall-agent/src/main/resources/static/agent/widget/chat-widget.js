/* ============================================================
 * 谷粒商城 AI 助手 - 用户端悬浮宠物 + 聊天窗
 * ------------------------------------------------------------
 * 引入方式（14 个 Thymeleaf 模板的 </body> 前）：
 *   <script src="/gulimall/agent/widget/chat-widget.js" defer></script>
 *
 * 依赖：无（原生 JS + fetch + SSE 解析）
 * ============================================================ */
(function () {
    'use strict';

    // 防止重复加载（有些页面可能被多次 include）
    if (window.__GL_AI_WIDGET_LOADED__) {
        return;
    }
    window.__GL_AI_WIDGET_LOADED__ = true;

    // 1. 基础常量与全局状态
    var API = '/gulimall/agent';
    var LOGIN_URL = 'http://auth.gulimall.vip/login.html';
    var AUTH_API = (window.location.protocol === 'https:' ? 'https://' : 'http://') + 'auth.gulimall.vip';
    var PET_STORAGE = 'glAiPetPosition';
    var THEME_STORAGE = 'glAiThemeColor';
    var PET_IMAGE = '/gulimall/agent/widget/pet/xbb/spritesheet.webp';
    // CSS 还没加载时 offsetWidth 可能是整行宽度，用这两个兜底值
    var PET_FALLBACK_W = 168;
    var PET_FALLBACK_H = 182;
    var state = {
        loggedIn: false,
        me: null,
        models: [],
        settings: {},
        customModels: [],
        conversations: [],
        conversationId: null,
        messages: [],
        filteredMessages: null,
        pendingImages: [],
        modelId: null,
        streaming: true,
        themeColor: '#e4393c',
        busy: false,
        abortController: null,
        canLoadEarlier: false,
        currentSearchKeyword: '',
        // 上方向键回显：当前停在用户消息列表的哪个下标，-1 表示没在回显
        recallIndex: -1,
        // 非登录类失败（服务刚启动、网络抖动）时记下原因，别误判成未登录
        loginError: null,
        // 图片异步生成：面板打开时轮询任务状态
        imageTaskTimer: null,
        imageTaskSeen: {},
        imageTaskInited: false,
        imageTaskNoticeShown: false
    };

    // 宠物精灵图：1536x2288，8 列 x 11 行，单元格 192x208
    var PET_STATES = {
        idle: { row: 0, frames: 6, durations: [280, 110, 110, 140, 140, 320], loop: true },
        right: { row: 1, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
        left: { row: 2, frames: 8, durations: [120, 120, 120, 120, 120, 120, 120, 220], loop: true },
        waving: { row: 3, frames: 4, durations: [140, 140, 140, 280], loop: false },
        jumping: { row: 4, frames: 5, durations: [140, 140, 140, 140, 280], loop: false },
        failed: { row: 5, frames: 8, durations: [140, 140, 140, 140, 140, 140, 140, 240], loop: false },
        waiting: { row: 6, frames: 6, durations: [150, 150, 150, 150, 150, 280], loop: true },
        running: { row: 7, frames: 6, durations: [120, 120, 120, 120, 120, 220], loop: true }
    };

    // 2. DOM 引用（init 时赋值）
    var rootEl = null;
    var petEl = null;
    var petTip = null;
    var panelEl = null;
    var messagesEl = null;
    var inputEl = null;
    var pendingEl = null;
    var modelSelectEl = null;
    var streamCheckEl = null;
    var historyModalEl = null;
    var historyListEl = null;
    var historySearchEl = null;
    var historyResultsEl = null;
    var settingsModalEl = null;
    var loginMaskEl = null;
    var colorPickerEl = null;
    var customPromptEl = null;
    var defaultModelEl = null;
    var defaultStreamEl = null;
    var customListEl = null;
    var toastEl = null;
    var fileInputEl = null;
    var currentSearchRowEl = null;
    var currentSearchEl = null;

    // 3. 宠物动画状态
    var petAnim = null;
    var petFrameState = 'idle';
    var dragState = null;
    var renderTimer = null;

    // ============================================================
    // 工具函数
    // ============================================================
    function el(tag, cls, text) {
        var node = document.createElement(tag);
        if (cls) node.className = cls;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function safeUrl(url) {
        if (!url) return '';
        var value = String(url).trim();
        if (/^https?:\/\//i.test(value) || value.indexOf('/oss/') === 0) {
            return value;
        }
        return '';
    }

    function formatTime(ts) {
        if (!ts) return '';
        var d = new Date(ts);
        var hh = String(d.getHours()).padStart(2, '0');
        var mm = String(d.getMinutes()).padStart(2, '0');
        return hh + ':' + mm;
    }

    function toast(message) {
        if (!toastEl) return;
        toastEl.textContent = message;
        toastEl.classList.add('gl-ai-show');
        clearTimeout(toast._timer);
        toast._timer = setTimeout(function () {
            toastEl.classList.remove('gl-ai-show');
        }, 2200);
    }

    function escapeRegExp(text) {
        return String(text).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    // ============================================================
    // 4. API 封装
    // ============================================================
    async function refreshAgentJwt() {
        var response = await fetch(AUTH_API + '/agent/jwt/refresh', {
            method: 'POST',
            credentials: 'include'
        });
        var text = await response.text();
        var json = {};
        try {
            json = text ? JSON.parse(text) : {};
        } catch (e) {
            throw new Error('JWT 刷新返回格式异常');
        }
        if (json.code === 15001) {
            state.loggedIn = false;
            showLoginMask(true);
        }
        if (json.code !== 0) {
            var error = new Error(json.msg || '登录已过期，请重新登录');
            error.code = json.code;
            throw error;
        }
        return json;
    }

    async function request(path, options, retried) {
        options = options || {};
        var headers = Object.assign({}, options.headers || {});
        if (!(options.body instanceof FormData)) {
            headers['Content-Type'] = 'application/json';
        }

        var response = await fetch(API + path, Object.assign({}, options, {
            credentials: 'include',
            headers: headers
        }));
        var text = await response.text();
        var json = {};
        try {
            json = text ? JSON.parse(text) : {};
        } catch (e) {
            throw new Error('服务返回格式异常');
        }

        // 1. 15012 表示 JWT 过期；15001 表示浏览器里连 JWT Cookie 都没有
        //    （JWT 只有 30 分钟，到期后浏览器会直接删除 Cookie），两种情况都值得先换一次
        // 2. 换新 JWT 用的是 7 天有效的常规登录态，换到就重试原请求
        // 3. retried 保证只重试一次；换不到说明登录态也失效了，交给下面的 15001 分支显示登录提示
        if ((json.code === 15012 || json.code === 15001) && !retried) {
            await refreshAgentJwt();
            return request(path, options, true);
        }
        if (json.code === 15001) {
            state.loggedIn = false;
            showLoginMask(true);
            var authError = new Error(json.msg || '请先登录');
            authError.code = json.code;
            throw authError;
        }
        if (json.code !== 0) {
            var error = new Error(json.msg || ('请求失败：' + json.code));
            error.code = json.code;
            throw error;
        }
        return json;
    }

    // ============================================================
    // 5. 构建 DOM
    // ============================================================
    function injectCss() {
        // 1. 只注入一次样式
        if (document.getElementById('gl-ai-widget-style')) return;
        var link = document.createElement('link');
        link.id = 'gl-ai-widget-style';
        link.rel = 'stylesheet';
        link.href = API + '/widget/chat-widget.css';
        document.head.appendChild(link);
    }

    function buildRoot() {
        rootEl = el('div', 'gl-ai-root');
        document.body.appendChild(rootEl);
        toastEl = el('div', 'gl-ai-toast');
        document.body.appendChild(toastEl);
    }

    function buildPet() {
        // 1. 创建宠物精灵（先入 DOM 再算尺寸，移动端 CSS 尺寸才准确）
        petEl = el('div', 'gl-ai-pet');
        rootEl.appendChild(petEl);
        applyPetSize();

        // 2. 悬浮提示
        petTip = el('div', 'gl-ai-pet-tip', '点我聊天，拖我换个位置');
        document.body.appendChild(petTip);

        // 3. 恢复上次位置
        var saved = null;
        try {
            saved = JSON.parse(localStorage.getItem(PET_STORAGE) || 'null');
        } catch (e) {
            saved = null;
        }
        if (saved && typeof saved.left === 'number' && typeof saved.top === 'number') {
            setPetPosition(saved.left, saved.top);
        } else {
            // 2. 默认停在右下角：用兜底尺寸算，CSS 还没加载也不会跑到左下角
            setPetPosition(window.innerWidth - petWidth() - 28,
                    window.innerHeight - petHeight() - 38);
        }

        // 4. 交互事件
        petEl.addEventListener('mouseenter', function () {
            showPetTip();
            if (!dragState && !state.busy) {
                playPet('waving', { onDone: function () { playPet('idle'); } });
            }
        });
        petEl.addEventListener('mouseleave', function () {
            hidePetTip();
            if (!dragState && !state.busy) playPet('idle');
        });
        petEl.addEventListener('pointerdown', startPetDrag);
        petEl.addEventListener('pointermove', movePetDrag);
        petEl.addEventListener('pointerup', endPetDrag);
        petEl.addEventListener('pointercancel', endPetDrag);

        playPet('idle');

        // 5. 精灵图加载失败时显示一个可点击的 emoji 兜底，
        //    避免 agent 刚重启/静态资源 404 时页面上什么都看不到
        checkPetImage();
    }

    function checkPetImage() {
        var probe = new Image();
        probe.onload = function () {
            petEl.classList.remove('gl-ai-pet-fallback');
        };
        probe.onerror = function () {
            petEl.classList.add('gl-ai-pet-fallback');
        };
        probe.src = PET_IMAGE;
    }

    function petWidth() {
        // 1. CSS 没加载时 block 元素会撑满整行，这个宽度不能当宠物宽度
        var w = petEl.offsetWidth;
        return (w > 0 && w < 400) ? w : PET_FALLBACK_W;
    }

    function petHeight() {
        var h = petEl.offsetHeight;
        return (h > 0 && h < 400) ? h : PET_FALLBACK_H;
    }

    function applyPetSize() {
        // 1. 按实际 CSS 尺寸计算背景缩放，移动端也适用
        var w = petWidth();
        var h = petHeight();
        petEl.style.backgroundSize = (w * 8) + 'px ' + (h * 11) + 'px';
        petEl._cellW = w;
        petEl._cellH = h;
    }

    function setPetPosition(left, top) {
        var maxLeft = Math.max(4, window.innerWidth - petWidth() - 4);
        var maxTop = Math.max(4, window.innerHeight - petHeight() - 4);
        petEl.style.left = Math.min(Math.max(4, left), maxLeft) + 'px';
        petEl.style.top = Math.min(Math.max(4, top), maxTop) + 'px';
        petEl.style.right = 'auto';
        petEl.style.bottom = 'auto';
    }

    function setPetFrame(row, col) {
        var w = petEl._cellW || PET_FALLBACK_W;
        var h = petEl._cellH || PET_FALLBACK_H;
        petEl.style.backgroundPosition = (-col * w) + 'px ' + (-row * h) + 'px';
    }

    function playPet(name, options) {
        options = options || {};
        var cfg = PET_STATES[name] || PET_STATES.idle;
        petFrameState = name;
        petAnim = {
            name: name,
            config: cfg,
            frame: 0,
            startedAt: performance.now(),
            loop: cfg.loop,
            onDone: options.onDone || null
        };
        setPetFrame(cfg.row, 0);
        requestAnimationFrame(petTick);
    }

    function petTick(now) {
        if (!petAnim) return;
        var anim = petAnim;
        var duration = anim.config.durations[anim.frame] || 120;
        if (now - anim.startedAt >= duration) {
            anim.frame += 1;
            if (anim.frame >= anim.config.frames) {
                if (anim.loop) {
                    anim.frame = 0;
                    anim.startedAt = now;
                } else {
                    var callback = anim.onDone;
                    petAnim = null;
                    setPetFrame(anim.config.row, anim.config.frames - 1);
                    if (callback) callback();
                    return;
                }
            } else {
                anim.startedAt = now;
            }
            setPetFrame(anim.config.row, anim.frame);
        }
        // 1. 如果这一帧期间 playPet 切换了动画，旧循环停止，避免两个 rAF 叠加导致动作变快
        if (petAnim !== anim) return;
        requestAnimationFrame(petTick);
    }

    function showPetTip() {
        petTip.style.left = (parseFloat(petEl.style.left || 0) - 20) + 'px';
        petTip.style.top = (parseFloat(petEl.style.top || 0) - 30) + 'px';
        petTip.classList.add('gl-ai-show');
    }

    function hidePetTip() {
        petTip.classList.remove('gl-ai-show');
    }

    function startPetDrag(event) {
        if (event.button !== 0 && event.pointerType === 'mouse') return;
        dragState = {
            pointerId: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            originLeft: parseFloat(petEl.style.left || 0),
            originTop: parseFloat(petEl.style.top || 0),
            moved: false,
            lastX: event.clientX
        };
        petEl.setPointerCapture(event.pointerId);
        petEl.classList.add('gl-ai-dragging');
        hidePetTip();
        event.preventDefault();
    }

    function movePetDrag(event) {
        if (!dragState || event.pointerId !== dragState.pointerId) return;
        var dx = event.clientX - dragState.startX;
        var dy = event.clientY - dragState.startY;
        if (!dragState.moved && Math.abs(dx) + Math.abs(dy) > 5) {
            dragState.moved = true;
            playPet('running');
        }
        if (!dragState.moved) return;

        // 1. 拖动过程中按水平方向播放左/右跑
        var direction = event.clientX - dragState.lastX;
        dragState.lastX = event.clientX;
        if (Math.abs(direction) > 2) {
            playPet(direction < 0 ? 'left' : 'right');
        }

        // 2. 更新位置
        setPetPosition(dragState.originLeft + dx, dragState.originTop + dy);
        event.preventDefault();
    }

    function endPetDrag(event) {
        if (!dragState || (event && event.pointerId !== dragState.pointerId)) return;
        var moved = dragState.moved;
        dragState = null;
        petEl.classList.remove('gl-ai-dragging');

        if (moved) {
            // 1. 拖动结束保存位置并回到待机
            var left = parseFloat(petEl.style.left || 0);
            var top = parseFloat(petEl.style.top || 0);
            try {
                localStorage.setItem(PET_STORAGE, JSON.stringify({ left: left, top: top }));
            } catch (e) { /* 忽略隐私模式报错 */ }
            playPet('idle');
            savePetPosition(left, top);
            return;
        }

        // 2. 没移动视为点击：登录后打开聊天
        if (!state.loggedIn) {
            toast('请先登录后再使用 AI 助手');
            openPanel();
            showLoginMask(true);
            return;
        }
        playPet('jumping', { onDone: function () { playPet('idle'); } });
        openPanel();
    }

    function savePetPosition(left, top) {
        // 1. 异步同步到设置，失败不影响本地使用
        if (!state.loggedIn) return;
        request('/settings', {
            method: 'PUT',
            body: JSON.stringify({ petPositionX: Math.round(left), petPositionY: Math.round(top) })
        }).catch(function () {});
    }

    function buildPanel() {
        panelEl = el('div', 'gl-ai-panel');
        panelEl.hidden = true;
        panelEl.innerHTML = [
            '<div class="gl-ai-header">',
            '  <div class="gl-ai-title-row">',
            '    <div class="gl-ai-title">谷粒商城 AI <small>购物助手</small></div>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="search" title="搜索当前对话">🔍</button>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="new" title="新建对话">＋</button>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="history" title="历史对话">🕘</button>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="settings" title="设置">⚙</button>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="resetSize" title="恢复默认大小">↺</button>',
            '    <button type="button" class="gl-ai-icon-btn" data-action="close" title="关闭">✕</button>',
            '  </div>',
            '  <div class="gl-ai-toolbar">',
            '    <select class="gl-ai-select" data-role="model" title="选择模型"></select>',
            '    <label class="gl-ai-switch"><input type="checkbox" data-role="stream"> 流式</label>',
            '  </div>',
            '</div>',
            '<div class="gl-ai-search-row" data-role="currentSearchRow" hidden style="padding:8px 10px 0;background:#f7f8fa;">',
            '  <input class="gl-ai-text-input" data-role="currentSearch" placeholder="搜索当前对话关键词">',
            '  <button type="button" class="gl-ai-small-btn" data-action="doCurrentSearch">搜索</button>',
            '  <button type="button" class="gl-ai-small-btn gl-ai-ghost" data-action="clearCurrentSearch">清除</button>',
            '</div>',
            '<div class="gl-ai-messages" data-role="messages"></div>',
            '<div class="gl-ai-composer">',
            '  <div class="gl-ai-pending" data-role="pending"></div>',
            '  <div class="gl-ai-input-row">',
            '    <button type="button" class="gl-ai-composer-btn" data-action="image" title="上传图片">🖼</button>',
            '    <textarea class="gl-ai-input" data-role="input" rows="1" placeholder="问我商品、订单、秒杀、优惠券；↑ 回显上一问；可 Ctrl+V 粘贴图片"></textarea>',
            '    <button type="button" class="gl-ai-composer-btn gl-ai-stop" data-action="stop" title="停止生成" hidden>■</button>',
            '    <button type="button" class="gl-ai-composer-btn gl-ai-send" data-action="send" title="发送">➤</button>',
            '  </div>',
            '  <div class="gl-ai-hint">AI 可能有误，请以商品页/订单页实际信息为准</div>',
            '  <input type="file" accept="image/jpeg,image/png,image/webp" data-role="file" hidden>',
            '</div>',
            '<div class="gl-ai-modal" data-role="historyModal" hidden>',
            '  <div class="gl-ai-sheet">',
            '    <div class="gl-ai-sheet-header">历史对话 <span style="flex:1"></span><button type="button" class="gl-ai-icon-btn" data-action="closeHistory">✕</button></div>',
            '    <div class="gl-ai-sheet-body">',
            '      <div class="gl-ai-search-row">',
            '        <input class="gl-ai-text-input" data-role="historySearch" placeholder="搜索 3 天内全部对话">',
            '        <button type="button" class="gl-ai-small-btn" data-action="searchHistory">搜索</button>',
            '      </div>',
            '      <div class="gl-ai-history-list" data-role="historyList"></div>',
            '      <div data-role="historyResults"></div>',
            '    </div>',
            '  </div>',
            '</div>',
            '<div class="gl-ai-modal" data-role="settingsModal" hidden>',
            '  <div class="gl-ai-sheet">',
            '    <div class="gl-ai-sheet-header">设置 <span style="flex:1"></span><button type="button" class="gl-ai-icon-btn" data-action="closeSettings">✕</button></div>',
            '    <div class="gl-ai-sheet-body">',
            '      <div class="gl-ai-field"><label>窗口颜色</label><div class="gl-ai-colors" data-role="colors"></div><input type="color" data-role="colorPicker" style="margin-top:8px;width:48px;height:30px;border:1px solid #e8eaed;border-radius:6px;padding:2px;"></div>',
            '      <div class="gl-ai-field"><label>默认模型</label><select class="gl-ai-text-input" data-role="defaultModel"></select></div>',
            '      <div class="gl-ai-inline"><input type="checkbox" data-role="defaultStream"> 默认开启流式输出</div>',
            '      <div class="gl-ai-field"><label>自定义提示词（只影响语气，不能改变权限）</label><textarea data-role="customPrompt" maxlength="500" placeholder="例如：回答尽量简短，多用列表"></textarea></div>',
            '      <button type="button" class="gl-ai-small-btn" data-action="saveSettings">保存设置</button>',
            '      <div class="gl-ai-divider"></div>',
            '      <div class="gl-ai-field"><label>我的自定义模型</label><div class="gl-ai-custom-list" data-role="customList"></div></div>',
            '      <div class="gl-ai-divider"></div>',
            '      <div class="gl-ai-field"><label>添加自定义模型</label>',
            '        <input class="gl-ai-text-input" data-role="cmName" placeholder="显示名称，例如 我的 GPT" style="margin-bottom:6px;">',
            '        <input class="gl-ai-text-input" data-role="cmModel" placeholder="modelName，例如 gpt-4o-mini" style="margin-bottom:6px;">',
            '        <input class="gl-ai-text-input" data-role="cmBase" placeholder="baseUrl，必须 https://" style="margin-bottom:6px;">',
            '        <input class="gl-ai-text-input" data-role="cmKey" placeholder="apiKey（不会明文保存）" style="margin-bottom:6px;">',
            '        <div class="gl-ai-inline"><input type="checkbox" data-role="cmShared"> 使用公共 closeai key</div>',
            '        <div class="gl-ai-inline"><input type="checkbox" data-role="cmVision"> 支持图片识别</div>',
            '        <button type="button" class="gl-ai-small-btn" data-action="addModel">添加模型</button>',
            '      </div>',
            '    </div>',
            '  </div>',
            '</div>',
            '<div class="gl-ai-login-mask" data-role="loginMask" hidden>',
            '  <button type="button" class="gl-ai-icon-btn gl-ai-login-close" data-action="close" title="关闭">✕</button>',
            '  <strong>登录后即可使用 AI 助手</strong>',
            '  <p>登录后回到本页会自动同步；没反应就点“我已登录”。</p>',
            '  <p class="gl-ai-login-hint" data-role="loginHint" hidden></p>',
            '  <div class="gl-ai-login-actions">',
            '    <a href="' + LOGIN_URL + '">去登录</a>',
            '    <button type="button" class="gl-ai-small-btn gl-ai-ghost" data-action="recheckLogin">我已登录</button>',
            '  </div>',
            '</div>',
            '<div class="gl-ai-resize gl-ai-resize-sw" data-resize="sw" title="拖动调整聊天框大小"></div>',
            '<div class="gl-ai-resize gl-ai-resize-se" data-resize="se" title="拖动调整聊天框大小"></div>'
        ].join('');
        rootEl.appendChild(panelEl);

        messagesEl = panelEl.querySelector('[data-role="messages"]');
        inputEl = panelEl.querySelector('[data-role="input"]');
        pendingEl = panelEl.querySelector('[data-role="pending"]');
        modelSelectEl = panelEl.querySelector('[data-role="model"]');
        streamCheckEl = panelEl.querySelector('[data-role="stream"]');
        historyModalEl = panelEl.querySelector('[data-role="historyModal"]');
        historyListEl = panelEl.querySelector('[data-role="historyList"]');
        historySearchEl = panelEl.querySelector('[data-role="historySearch"]');
        historyResultsEl = panelEl.querySelector('[data-role="historyResults"]');
        settingsModalEl = panelEl.querySelector('[data-role="settingsModal"]');
        loginMaskEl = panelEl.querySelector('[data-role="loginMask"]');
        colorPickerEl = panelEl.querySelector('[data-role="colorPicker"]');
        customPromptEl = panelEl.querySelector('[data-role="customPrompt"]');
        defaultModelEl = panelEl.querySelector('[data-role="defaultModel"]');
        defaultStreamEl = panelEl.querySelector('[data-role="defaultStream"]');
        customListEl = panelEl.querySelector('[data-role="customList"]');
        fileInputEl = panelEl.querySelector('[data-role="file"]');
        currentSearchRowEl = panelEl.querySelector('[data-role="currentSearchRow"]');
        currentSearchEl = panelEl.querySelector('[data-role="currentSearch"]');

        bindPanelEvents();
        bindPanelResize();
        renderColorDots();
    }

    /**
     * 把聊天框恢复成默认大小。拖动尺寸只对当前页面有效，不写 localStorage。
     */
    function resetPanelSize() {
        applyPanelSize(388, 580, 24);
    }

    /**
     * 把聊天框宽高限制在可视范围内并写回样式。
     *
     * @param {number} width 目标宽度
     * @param {number} height 目标高度
     * @returns {{width:number,height:number}} 实际生效的尺寸
     */
    function applyPanelSize(width, height, right) {
        // 1. 最小 320x360，最大不超过当前窗口；底部留出 220px 给输入区/页面
        var minWidth = 320;
        var minHeight = 360;
        var finalRight = typeof right === 'number'
            ? Math.max(8, right)
            : (parseFloat(panelEl.style.right || '') || 24);
        finalRight = Math.max(8, Math.min(finalRight, window.innerWidth - minWidth - 8));
        var maxWidth = Math.max(minWidth, window.innerWidth - finalRight - 8);
        var maxHeight = Math.max(minHeight, window.innerHeight - 220);
        var finalWidth = Math.min(Math.max(minWidth, width), maxWidth);
        var finalHeight = Math.min(Math.max(minHeight, height), maxHeight);
        // 2. 固定定位用 right/bottom；右下角拖动时同步移动右边缘，左下角拖动时右边缘不动
        panelEl.style.right = finalRight + 'px';
        panelEl.style.width = finalWidth + 'px';
        panelEl.style.height = finalHeight + 'px';
        return { width: finalWidth, height: finalHeight };
    }

    /**
     * 给左下角、右下角两个拖拽柄绑定事件。
     */
    function bindPanelResize() {
        var handles = panelEl.querySelectorAll('[data-resize]');
        for (var i = 0; i < handles.length; i++) {
            handles[i].addEventListener('pointerdown', startPanelResize);
        }
    }

    /**
     * 开始拖动聊天框边缘。
     *
     * @param {PointerEvent} event 指针按下事件
     */
    function startPanelResize(event) {
        if (event.button !== 0 && event.pointerType === 'mouse') return;
        var handle = event.currentTarget;
        var rect = panelEl.getBoundingClientRect();
        var drag = {
            pointerId: event.pointerId,
            side: handle.getAttribute('data-resize'),
            startX: event.clientX,
            startY: event.clientY,
            startWidth: rect.width,
            startHeight: rect.height,
            startRight: window.innerWidth - rect.right
        };

        // 1. 捕获指针，拖出小柄范围也能继续收到 move/up
        handle.setPointerCapture(event.pointerId);

        function onMove(moveEvent) {
            if (moveEvent.pointerId !== drag.pointerId) return;
            var dx = moveEvent.clientX - drag.startX;
            var dy = moveEvent.clientY - drag.startY;
            // 2. 左下角向左拖变宽，右下角向右拖变宽；右下角同时把右边缘跟着鼠标移动
            var nextWidth = drag.startWidth + (drag.side === 'sw' ? -dx : dx);
            var nextHeight = drag.startHeight + dy;
            var nextRight = drag.side === 'se' ? drag.startRight - dx : drag.startRight;
            applyPanelSize(nextWidth, nextHeight, nextRight);
            moveEvent.preventDefault();
        }

        function onEnd(endEvent) {
            if (endEvent.pointerId !== drag.pointerId) return;
            // 3. 拖动结束只保留当前页面尺寸，不写 localStorage；刷新后回到默认大小
            applyPanelSize(panelEl.offsetWidth, panelEl.offsetHeight);
            handle.removeEventListener('pointermove', onMove);
            handle.removeEventListener('pointerup', onEnd);
            handle.removeEventListener('pointercancel', onEnd);
        }

        handle.addEventListener('pointermove', onMove);
        handle.addEventListener('pointerup', onEnd);
        handle.addEventListener('pointercancel', onEnd);
        event.preventDefault();
    }

    // ============================================================
    // 6. 面板事件
    // ============================================================
    function bindPanelEvents() {
        panelEl.addEventListener('click', function (event) {
            var actionEl = event.target.closest('[data-action]');
            if (!actionEl) return;
            var action = actionEl.getAttribute('data-action');
            if (action === 'close') closePanel();
            if (action === 'new') newConversation();
            if (action === 'history') openHistory();
            if (action === 'closeHistory') historyModalEl.hidden = true;
            if (action === 'settings') openSettings();
            if (action === 'resetSize') resetPanelSize();
            if (action === 'closeSettings') settingsModalEl.hidden = true;
            if (action === 'send') sendMessage();
            if (action === 'stop') stopStream();
            if (action === 'image') fileInputEl.click();
            if (action === 'search') toggleCurrentSearch();
            if (action === 'doCurrentSearch') doCurrentSearch();
            if (action === 'clearCurrentSearch') clearCurrentSearch();
            if (action === 'searchHistory') searchAllHistory();
            if (action === 'saveSettings') saveSettings();
            if (action === 'addModel') addCustomModel();
            if (action === 'recheckLogin') manualRecheckLogin();
        });

        inputEl.addEventListener('input', function () {
            autoResizeInput();
            // 1. 用户手动改过输入框，下一次按 ↑ 重新从最新一条开始
            state.recallIndex = -1;
        });
        inputEl.addEventListener('paste', handlePasteImage);
        inputEl.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
                return;
            }
            if (event.key === 'ArrowUp') {
                if (recallPreviousInput()) event.preventDefault();
                return;
            }
            if (event.key === 'ArrowDown') {
                if (recallNextInput()) event.preventDefault();
            }
        });
        streamCheckEl.addEventListener('change', function () {
            state.streaming = streamCheckEl.checked;
            saveSettingsSilent();
        });
        modelSelectEl.addEventListener('change', function () {
            state.modelId = modelSelectEl.value;
            saveSettingsSilent();
        });
        fileInputEl.addEventListener('change', uploadImage);
        currentSearchEl.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') doCurrentSearch();
        });
        historySearchEl.addEventListener('keydown', function (event) {
            if (event.key === 'Enter') searchAllHistory();
        });
        colorPickerEl.addEventListener('input', function () {
            setThemeColor(colorPickerEl.value, false);
        });
        panelEl.querySelector('[data-role="colors"]').addEventListener('click', function (event) {
            var dot = event.target.closest('[data-color]');
            if (dot) setThemeColor(dot.getAttribute('data-color'), true);
        });
        panelEl.querySelector('[data-role="cmShared"]').addEventListener('change', function (event) {
            var keyInput = panelEl.querySelector('[data-role="cmKey"]');
            keyInput.disabled = event.target.checked;
            if (event.target.checked) keyInput.value = '';
        });
        messagesEl.addEventListener('click', function (event) {
            var img = event.target.closest('img');
            if (img && img.src) openLightbox(img.src);
        });
    }

    function autoResizeInput() {
        inputEl.style.height = 'auto';
        inputEl.style.height = Math.min(inputEl.scrollHeight, 96) + 'px';
    }

    // ============================================================
    // 6.1 上方向键回显上一问（文字 + 图片）
    // ============================================================
    function userHistory() {
        // 1. 只回显当前对话里的用户消息，助手回复跳过
        return state.messages.filter(function (message) {
            return message.role === 'user';
        });
    }

    function recallPreviousInput() {
        var history = userHistory();
        if (!history.length) return false;

        if (state.recallIndex === -1) {
            // 2. 输入框里已有草稿时不动它，避免覆盖用户正在写的内容
            if ((inputEl.value || '').trim() || state.pendingImages.length) return false;
            state.recallIndex = history.length - 1;
        } else if (state.recallIndex > 0) {
            state.recallIndex -= 1;
        } else {
            return true;
        }

        applyRecalledMessage(history[state.recallIndex]);
        return true;
    }

    function recallNextInput() {
        if (state.recallIndex === -1) return false;

        var history = userHistory();
        if (state.recallIndex < history.length - 1) {
            state.recallIndex += 1;
            applyRecalledMessage(history[state.recallIndex]);
        } else {
            // 3. 已经回到最新一条，再按 ↓ 清空
            state.recallIndex = -1;
            inputEl.value = '';
            state.pendingImages = [];
            renderPendingImages();
            autoResizeInput();
        }
        return true;
    }

    function applyRecalledMessage(message) {
        // 4. 文字和图片一起回显；[图片] 只是占位文案，不该填进输入框
        var text = message.content || '';
        inputEl.value = text === '[图片]' ? '' : text;
        autoResizeInput();

        var imageIds = message.imageIds || [];
        var imageUrls = message.imageUrls || [];
        state.pendingImages = imageIds.map(function (imageId, index) {
            return { imageId: imageId, url: imageUrls[index] || '' };
        });
        renderPendingImages();
        inputEl.focus();
    }

    async function openPanel() {
        panelEl.hidden = false;

        // 1. 打开面板时重新查一次登录态，避免登录后旧页面还显示“未登录”
        if (!state.loggedIn) {
            await checkLogin();
        }

        if (state.loggedIn) {
            showLoginMask(false);
            if (!state.models.length) {
                await loadAll();
            }
            // 2. 后台图片生成完成后要自动显示，面板打开期间轮询任务状态
            startImageTaskPolling();
        } else {
            showLoginMask(true);
        }
        setTimeout(function () { inputEl.focus(); }, 120);
    }

    function closePanel() {
        panelEl.hidden = true;
        historyModalEl.hidden = true;
        settingsModalEl.hidden = true;
        stopImageTaskPolling();
    }

    // ============================================================
    // 6.2 图片异步生成：轮询任务状态
    // ============================================================
    function startImageTaskPolling() {
        stopImageTaskPolling();
        state.imageTaskSeen = {};
        state.imageTaskInited = false;
        state.imageTaskNoticeShown = false;
        if (!state.loggedIn) return;
        state.imageTaskTimer = setInterval(pollImageTasks, 4000);
    }

    function stopImageTaskPolling() {
        if (state.imageTaskTimer) {
            clearInterval(state.imageTaskTimer);
            state.imageTaskTimer = null;
        }
    }

    function pollImageTasks() {
        if (!state.loggedIn || !state.conversationId) return;

        request('/images/tasks?conversationId=' + encodeURIComponent(state.conversationId))
            .then(function (res) {
                var list = res.list || [];

                // 1. 第一次只记下已有任务，避免打开面板时把历史图片当成“新生成”
                if (!state.imageTaskInited) {
                    state.imageTaskInited = true;
                    list.forEach(function (task) { state.imageTaskSeen[task.id] = true; });
                    if (list.some(function (task) { return task.status === 'DONE'; })) {
                        loadMessages();
                    }
                    return;
                }

                // 2. 有任务在跑时提示一次
                var pending = list.filter(function (task) {
                    return task.status === 'PENDING' || task.status === 'RUNNING';
                });
                if (pending.length && !state.imageTaskNoticeShown) {
                    state.imageTaskNoticeShown = true;
                    toast('图片正在后台生成，完成后会自动显示');
                }

                // 3. 新失败任务提示原因
                var failed = list.filter(function (task) {
                    return task.status === 'FAILED' && !state.imageTaskSeen[task.id];
                });
                failed.forEach(function (task) {
                    state.imageTaskSeen[task.id] = true;
                    toast('图片生成失败：' + (task.error || '未知错误'));
                });
                if (failed.length && !state.busy) {
                    loadMessages();
                }

                // 4. 新完成任务：刷新消息，图片会作为一条新 AI 消息出现
                var done = list.filter(function (task) {
                    return task.status === 'DONE' && !state.imageTaskSeen[task.id];
                });
                if (done.length && !state.busy) {
                    done.forEach(function (task) { state.imageTaskSeen[task.id] = true; });
                    loadMessages().then(function () {
                        state.imageTaskNoticeShown = false;
                        toast('图片已生成');
                    }).catch(function () {});
                }
            })
            .catch(function () { /* 轮询失败下次再试 */ });
    }

    function showLoginMask(show) {
        loginMaskEl.hidden = !show;
        if (!show) return;

        // 1. localhost 和 auth.gulimall.vip 不同域，浏览器的登录 cookie 不会带过来，
        //    这里直接提示，避免用户以为是登录功能坏了
        var hint = loginMaskEl.querySelector('[data-role="loginHint"]');
        if (!hint) return;
        var host = window.location.hostname;
        if (host === 'localhost' || host === '127.0.0.1') {
            hint.textContent = '当前用 ' + host + ' 访问，登录 cookie 属于 gulimall.vip 域，请改用 http://gulimall.vip 打开本页。';
            hint.hidden = false;
        } else {
            hint.hidden = true;
        }
    }

    // ============================================================
    // 7. 主题
    // ============================================================
    function renderColorDots() {
        var colors = ['#e4393c', '#3b82f6', '#10b981', '#8b5cf6', '#f59e0b', '#111827'];
        var box = panelEl.querySelector('[data-role="colors"]');
        box.innerHTML = '';
        colors.forEach(function (color) {
            var dot = el('span', 'gl-ai-color-dot');
            dot.style.background = color;
            dot.setAttribute('data-color', color);
            dot.title = color;
            box.appendChild(dot);
        });
        applyTheme();
    }

    function setThemeColor(color, persist) {
        if (!/^#[0-9a-fA-F]{6}$/.test(color)) return;
        state.themeColor = color;
        applyTheme();
        try { localStorage.setItem(THEME_STORAGE, color); } catch (e) { /* ignore */ }
        if (persist) saveSettingsSilent();
    }

    function applyTheme() {
        var color = state.themeColor || localStorage.getItem(THEME_STORAGE) || '#e4393c';
        rootEl.style.setProperty('--gl-ai-primary', color);
        if (colorPickerEl) colorPickerEl.value = color;
        var dots = panelEl ? panelEl.querySelectorAll('[data-color]') : [];
        for (var i = 0; i < dots.length; i++) {
            dots[i].classList.toggle('gl-ai-color-active', dots[i].getAttribute('data-color') === color);
        }
    }

    // ============================================================
    // 8. 数据加载
    // ============================================================
    async function loadAll() {
        try {
            await loadSettings();
            await loadModels();
            await loadConversations();
        } catch (e) {
            toast(e.message || '加载失败');
        }
    }

    async function loadSettings() {
        var res = await request('/settings');
        state.settings = res.setting || {};
        state.customModels = res.customModels || [];
        state.themeColor = state.settings.themeColor || localStorage.getItem(THEME_STORAGE) || '#e4393c';
        state.streaming = state.settings.streamEnabled !== false;
        state.modelId = state.settings.defaultModel || state.modelId;
        applyTheme();
        applyPetVisibility();
        syncSettingsForm();
    }

    async function loadModels() {
        var res = await request('/models');
        state.models = res.models || [];
        populateModelSelects();
    }

    function populateModelSelects() {
        // 1. 主聊天框模型下拉
        modelSelectEl.innerHTML = '';
        defaultModelEl.innerHTML = '';
        state.models.forEach(function (model) {
            var label = model.displayName
                + (model.custom ? '（自定义）' : '')
                + (model.vision ? ' · 看图' : '')
                + (model.tools ? '' : ' · 不支持查库');
            var option = el('option', null, label);
            option.value = model.id;
            modelSelectEl.appendChild(option);

            var option2 = option.cloneNode(true);
            defaultModelEl.appendChild(option2);
        });

        // 2. 选中的模型不存在时回退到第一个
        if (!state.modelId || !state.models.some(function (m) { return m.id === state.modelId; })) {
            state.modelId = state.models.length ? state.models[0].id : null;
        }
        if (state.modelId) {
            modelSelectEl.value = state.modelId;
            defaultModelEl.value = state.modelId;
        }
    }

    async function loadConversations() {
        var res = await request('/conversations');
        state.conversations = res.list || [];
        if (!state.conversations.length) {
            await createConversation();
            return;
        }
        if (!state.conversationId || !state.conversations.some(function (c) { return c.id === state.conversationId; })) {
            state.conversationId = state.conversations[0].id;
        }
        await loadMessages();
        renderHistoryList();
    }

    async function createConversation() {
        var res = await request('/conversations', {
            method: 'POST',
            body: JSON.stringify({ modelId: state.modelId })
        });
        state.conversationId = res.conversationId;
        state.messages = [];
        state.canLoadEarlier = false;
        state.filteredMessages = null;
        state.recallIndex = -1;
        renderMessages();
        await refreshConversations();
    }

    async function refreshConversations() {
        try {
            var res = await request('/conversations');
            state.conversations = res.list || [];
            renderHistoryList();
        } catch (e) { /* 忽略 */ }
    }

    async function loadMessages(before) {
        if (!state.conversationId) return;
        var path = '/conversations/' + encodeURIComponent(state.conversationId) + '/messages?limit=20';
        if (before) path += '&before=' + encodeURIComponent(before);
        var res = await request(path);
        var list = res.list || [];
        if (before) {
            state.messages = list.concat(state.messages);
            state.canLoadEarlier = list.length >= 20;
        } else {
            state.messages = list;
            state.canLoadEarlier = list.length >= 20;
            state.filteredMessages = null;
            state.currentSearchKeyword = '';
        }
        // 消息列表变了，回显下标作废
        state.recallIndex = -1;
        renderMessages();
    }

    async function loadEarlier() {
        if (!state.messages.length) return;
        await loadMessages(state.messages[0].id);
    }

    async function newConversation() {
        if (!state.loggedIn) {
            showLoginMask(true);
            return;
        }
        try {
            // 1. 先停止上一轮还在生成的回答，避免旧请求继续跑
            if (state.busy) {
                await stopStream();
            }
            await createConversation();
            // 2. 新对话从空白开始，和管理端“新对话”保持一致
            inputEl.value = '';
            state.pendingImages = [];
            state.recallIndex = -1;
            renderPendingImages();
            autoResizeInput();
            startImageTaskPolling();
            historyModalEl.hidden = true;
            toast('已新建对话');
        } catch (e) {
            toast(e.message);
        }
    }

    // ============================================================
    // 9. 消息渲染
    // ============================================================
    function renderMessages() {
        var list = state.filteredMessages || state.messages;
        messagesEl.innerHTML = '';

        if (state.filteredMessages) {
            var bar = el('div', 'gl-ai-empty');
            bar.innerHTML = '当前对话搜索结果：<strong>' + escapeHtml(state.currentSearchKeyword) + '</strong>';
            messagesEl.appendChild(bar);
        } else if (!list.length) {
            var empty = el('div', 'gl-ai-empty');
            empty.innerHTML = '<strong>你好，我是谷粒商城 AI 购物助手</strong>'
                + '可以问我：商品推荐、库存价格、购物车、订单、秒杀、优惠券、天气和位置。';
            messagesEl.appendChild(empty);
            return;
        }

        if (state.canLoadEarlier && !state.filteredMessages) {
            var more = el('div', 'gl-ai-empty');
            var moreBtn = el('button', 'gl-ai-small-btn gl-ai-ghost', '加载更早的消息');
            moreBtn.addEventListener('click', loadEarlier);
            more.appendChild(moreBtn);
            messagesEl.appendChild(more);
        }

        list.forEach(function (message) {
            messagesEl.appendChild(buildMessageNode(message));
        });
        scrollMessagesToBottom();
    }

    function buildMessageNode(message) {
        var isUser = message.role === 'user';
        var wrap = el('div', 'gl-ai-msg ' + (isUser ? 'gl-ai-user' : 'gl-ai-assistant'));
        wrap.setAttribute('data-mid', message.id || '');

        var avatar = el('div', 'gl-ai-avatar', isUser ? '我' : 'AI');
        wrap.appendChild(avatar);

        var bubbleWrap = el('div', 'gl-ai-bubble-wrap');
        var bubble = el('div', 'gl-ai-bubble');

        // 1. 用户上传的图片
        if (message.imageUrls && message.imageUrls.length) {
            var images = el('div', 'gl-ai-images');
            message.imageUrls.forEach(function (url) {
                var safe = safeUrl(url);
                if (!safe) return;
                var img = document.createElement('img');
                img.src = safe;
                img.alt = '上传图片';
                images.appendChild(img);
            });
            bubble.appendChild(images);
        }

        // 2. 正文：流式中显示打字动画
        var content = el('div', 'gl-ai-content');
        content.innerHTML = renderMarkdown(message.content || '');
        if (message.streaming) {
            content.innerHTML += '<span class="gl-ai-typing"><span></span><span></span><span></span></span>';
        }
        bubble.appendChild(content);

        // 3. 时间和 token
        var time = el('div', 'gl-ai-time', formatTime(message.createdAt)
            + (message.outputTokens ? ' · ' + message.outputTokens + ' tokens' : ''));
        bubble.appendChild(time);

        bubbleWrap.appendChild(bubble);
        wrap.appendChild(bubbleWrap);
        return wrap;
    }

    function renderMarkdown(text) {
        // 1. 先转义，避免 XSS；再还原 Markdown 结构
        var safe = escapeHtml(text || '');

        // 2. 代码块单独保护，内部不再做 Markdown 替换
        var codeBlocks = [];
        safe = safe.replace(/```([\s\S]*?)```/g, function (match, code) {
            codeBlocks.push(code);
            return ' CODE' + (codeBlocks.length - 1) + ' ';
        });

        // 3. 图片：只允许 /oss/ 或 http(s)
        safe = safe.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, function (match, alt, url) {
            var safeLink = safeUrl(url.replace(/&amp;/g, '&'));
            if (!safeLink) return '';
            return '<img src="' + safeLink + '" alt="' + alt + '">';
        });

        // 4. 链接
        safe = safe.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, function (match, label, url) {
            var safeLink = safeUrl(url.replace(/&amp;/g, '&'));
            if (!safeLink) return label;
            return '<a href="' + safeLink + '" target="_blank" rel="noopener">' + label + '</a>';
        });

        // 5. 行内代码、加粗、换行
        safe = safe.replace(/`([^`]+)`/g, '<code>$1</code>');
        safe = safe.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        safe = safe.replace(/\n/g, '<br>');

        // 6. 还原代码块
        safe = safe.replace(/ CODE(\d+) /g, function (match, index) {
            return '<pre><code>' + codeBlocks[Number(index)] + '</code></pre>';
        });
        return safe;
    }

    function updateStreamingMessage(message) {
        var node = messagesEl.querySelector('[data-mid="' + message.id + '"] .gl-ai-content');
        if (!node) {
            scheduleRender();
            return;
        }
        node.innerHTML = renderMarkdown(message.content || '')
            + (message.streaming ? '<span class="gl-ai-typing"><span></span><span></span><span></span></span>' : '');
        scrollMessagesToBottom();
    }

    function scheduleRender() {
        if (renderTimer) return;
        renderTimer = setTimeout(function () {
            renderTimer = null;
            renderMessages();
        }, 60);
    }

    function scrollMessagesToBottom() {
        messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function openLightbox(src) {
        var mask = el('div', 'gl-ai-modal');
        mask.style.position = 'fixed';
        mask.style.zIndex = '2147483004';
        mask.style.alignItems = 'center';
        var img = document.createElement('img');
        img.src = src;
        img.style.maxWidth = '92vw';
        img.style.maxHeight = '92vh';
        img.style.borderRadius = '12px';
        img.style.background = '#fff';
        mask.appendChild(img);
        mask.addEventListener('click', function () { mask.remove(); });
        document.body.appendChild(mask);
    }

    // ============================================================
    // 10. 发送消息
    // ============================================================
    async function sendMessage() {
        if (state.busy) return;
        if (!state.loggedIn) {
            showLoginMask(true);
            toast('请先登录后再使用 AI 助手');
            return;
        }

        var text = (inputEl.value || '').trim();
        if (!text && !state.pendingImages.length) return;
        if (!state.conversationId) {
            await createConversation();
        }

        // 1. 先把待发送图片的 id/url 取出来，再清空预览
        var imageIds = state.pendingImages.map(function (img) { return img.imageId; });
        var imageUrls = state.pendingImages.map(function (img) { return img.url; });

        // 2. 乐观渲染用户消息
        var userMessage = {
            id: 'local-' + Date.now(),
            role: 'user',
            content: text || '[图片]',
            imageUrls: imageUrls,
            // 1. 本地消息也带上 imageId，按 ↑ 回显后还能重新发送
            imageIds: imageIds,
            createdAt: Date.now(),
            status: 'ok'
        };
        state.messages.push(userMessage);
        state.filteredMessages = null;
        inputEl.value = '';
        state.recallIndex = -1;
        autoResizeInput();
        state.pendingImages = [];
        renderPendingImages();
        renderMessages();
        setBusy(true);

        var body = {
            conversationId: state.conversationId,
            message: text,
            modelId: state.modelId,
            imageIds: imageIds
        };

        try {
            if (state.streaming) {
                await sendStream(body);
            } else {
                await sendNormal(body);
            }
        } catch (e) {
            // 用户主动停止会产生 AbortError，不当成失败提示
            if (e && e.name === 'AbortError') {
                return;
            }
            // 1. 异常退出时把最后一条流式占位消息收尾，避免一直显示三个打字点
            var lastMessage = state.messages[state.messages.length - 1];
            if (lastMessage && lastMessage.streaming) {
                lastMessage.streaming = false;
                lastMessage.status = 'error';
                if (!lastMessage.content) {
                    lastMessage.content = e.message || 'AI 回复中断，请重试';
                }
                updateStreamingMessage(lastMessage);
            }
            if (state.loggedIn) {
                toast(e.message || '发送失败');
                playPet('failed', { onDone: function () { playPet('idle'); } });
            }
        } finally {
            state.abortController = null;
            setBusy(false);
            refreshConversations();
        }
    }

    async function sendNormal(body) {
        var res = await request('/chat', {
            method: 'POST',
            body: JSON.stringify(body)
        });
        var data = res.data || {};
        state.conversationId = data.conversationId || state.conversationId;
        state.messages.push({
            id: data.messageId,
            role: 'assistant',
            content: data.content,
            modelId: data.modelId,
            createdAt: data.createdAt,
            inputTokens: data.inputTokens,
            outputTokens: data.outputTokens,
            status: 'ok'
        });
        renderMessages();
    }

    async function sendStream(body, retried) {
        // 1. 插入一条占位消息
        var placeholder = {
            id: 'stream-' + Date.now(),
            role: 'assistant',
            content: '',
            modelId: state.modelId,
            createdAt: Date.now(),
            streaming: true,
            status: 'ok'
        };
        state.messages.push(placeholder);
        renderMessages();

        // 2. POST SSE；保存 AbortController，停止按钮可以中断连接
        state.abortController = new AbortController();
        var response;
        try {
            response = await fetch(API + '/chat/stream', {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream' },
                body: JSON.stringify(body),
                signal: state.abortController.signal
            });
        } catch (e) {
            if (e && e.name === 'AbortError') {
                return;
            }
            throw e;
        }

        var contentType = response.headers.get('content-type') || '';
        if (contentType.indexOf('text/event-stream') < 0) {
            // 未登录等情况下后端返回 JSON
            var text = await response.text();
            var json = {};
            try { json = JSON.parse(text); } catch (e) { /* ignore */ }
            if (json.code === 15012 && !retried) {
                await refreshAgentJwt();
                return sendStream(body, true);
            }
            if (json.code === 15001) {
                state.loggedIn = false;
                showLoginMask(true);
                throw new Error(json.msg || '请先登录');
            }
            throw new Error(json.msg || '流式请求失败');
        }

        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        var buffer = '';
        var jwtExpired = false;

        while (true) {
            var step = await reader.read();
            if (step.done) break;
            buffer += decoder.decode(step.value, { stream: true });
            buffer = buffer.replace(/\r/g, '');
            var index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                var chunk = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                handleSseChunk(chunk, placeholder, function () { jwtExpired = true; });
            }
        }

        if (jwtExpired) {
            var pos = state.messages.indexOf(placeholder);
            if (pos >= 0) {
                state.messages.splice(pos, 1);
                renderMessages();
            }
            if (retried) {
                state.loggedIn = false;
                showLoginMask(true);
                throw new Error('登录已过期，请重新登录');
            }
            await refreshAgentJwt();
            return sendStream(body, true);
        }

        if (placeholder.streaming) {
            // 1. 流结束但没收到 done/error，说明连接被中断或服务端回调异常
            placeholder.streaming = false;
            placeholder.status = 'error';
            // 2. 没有内容时补一句可读提示，避免只剩三个打字点
            if (!placeholder.content) {
                placeholder.content = 'AI 回复中断，请重试';
            }
            updateStreamingMessage(placeholder);
            toast('AI 回复中断，请重试');
        }
    }

    function handleSseChunk(chunk, placeholder, onJwtExpired) {
        // 1. 解析 event/data 两行
        var eventName = 'message';
        var data = '';
        chunk.split('\n').forEach(function (line) {
            if (line.indexOf('event:') === 0) eventName = line.slice(6).trim();
            if (line.indexOf('data:') === 0) data += line.slice(5).trim();
        });
        if (!data) return;

        var payload = {};
        try { payload = JSON.parse(data); } catch (e) { return; }

        // 2. 按事件处理
        if (eventName === 'meta') {
            state.conversationId = payload.conversationId || state.conversationId;
            placeholder.id = payload.messageId || placeholder.id;
            placeholder.modelId = payload.modelId || placeholder.modelId;
            renderMessages();
            return;
        }
        if (eventName === 'delta') {
            placeholder.content += payload.content || '';
            updateStreamingMessage(placeholder);
            return;
        }
        if (eventName === 'done') {
            placeholder.streaming = false;
            placeholder.id = payload.messageId || placeholder.id;
            placeholder.inputTokens = payload.inputTokens;
            placeholder.outputTokens = payload.outputTokens;
            updateStreamingMessage(placeholder);
            return;
        }
        if (eventName === 'error') {
            if ((payload.code === 15012 || payload.code === 15001) && onJwtExpired) {
                placeholder.streaming = false;
                onJwtExpired();
                return;
            }
            placeholder.streaming = false;
            placeholder.status = 'error';
            placeholder.content = payload.message || 'AI 调用失败';
            updateStreamingMessage(placeholder);
            toast(placeholder.content);
        }
    }

    async function stopStream() {
        // 1. 只要还有流式占位消息就允许停止，即便 busy 已因异常被重置
        var hasStreaming = state.messages.some(function (message) {
            return message.streaming;
        });
        if (!state.busy && !hasStreaming) return;

        // 1. 先通知服务端停止当前 streamId
        var conversationId = state.conversationId;
        if (conversationId) {
            try {
                await request('/chat/stop', {
                    method: 'POST',
                    body: JSON.stringify({ conversationId: conversationId })
                });
            } catch (e) { /* 停止失败也让前端断开 */ }
        }

        // 2. 中断 fetch
        if (state.abortController) {
            try { state.abortController.abort(); } catch (e) { /* ignore */ }
            state.abortController = null;
        }

        // 3. 把最后一条流式消息标记为已停止
        var last = state.messages[state.messages.length - 1];
        if (last && last.streaming) {
            last.streaming = false;
            if (!last.content) last.content = '（已停止）';
        }
        renderMessages();
        setBusy(false);
        toast('已停止生成');
    }

    function setBusy(busy) {
        state.busy = busy;
        var sendBtn = panelEl.querySelector('[data-action="send"]');
        if (sendBtn) sendBtn.disabled = busy;
        // 1. 只要还有消息在流式输出，停止按钮就保持可见；
        //    不能只依赖 busy，否则异常结束后仍会留下打字动画但没有停止入口
        var hasStreaming = state.messages.some(function (message) {
            return message.streaming;
        });
        var stopBtn = panelEl.querySelector('[data-action="stop"]');
        if (stopBtn) stopBtn.hidden = !(busy || hasStreaming);
        if (busy) {
            playPet('running');
        } else if (petFrameState !== 'failed') {
            playPet('idle');
        }
    }

    // ============================================================
    // 11. 图片上传
    // ============================================================
    async function uploadImage() {
        var file = fileInputEl.files && fileInputEl.files[0];
        fileInputEl.value = '';
        if (!file) return;
        await uploadFile(file);
    }

    async function handlePasteImage(event) {
        var clipboard = event.clipboardData || window.clipboardData;
        if (!clipboard || !clipboard.items) return;
        for (var i = 0; i < clipboard.items.length; i++) {
            var item = clipboard.items[i];
            if (item.type && item.type.indexOf('image/') === 0) {
                var file = item.getAsFile();
                if (file) {
                    event.preventDefault();
                    await uploadFile(file);
                }
                return;
            }
        }
    }

    async function uploadFile(file) {
        if (!state.loggedIn) {
            showLoginMask(true);
            return;
        }
        if (file.size > 8 * 1024 * 1024) {
            toast('图片不能超过 8MB');
            return;
        }

        var form = new FormData();
        form.append('file', file);
        try {
            toast('正在上传图片...');
            var res = await request('/images', { method: 'POST', body: form });
            state.pendingImages.push(res.data);
            renderPendingImages();
            toast('图片已上传');
        } catch (e) {
            toast(e.message || '上传失败');
        }
    }

    function renderPendingImages() {
        pendingEl.innerHTML = '';
        state.pendingImages.forEach(function (image, index) {
            var chip = el('div', 'gl-ai-chip');
            var img = document.createElement('img');
            img.src = image.url;
            img.alt = '待发送图片';
            chip.appendChild(img);
            var remove = el('button', null, '×');
            remove.type = 'button';
            remove.addEventListener('click', function () {
                state.pendingImages.splice(index, 1);
                renderPendingImages();
            });
            chip.appendChild(remove);
            pendingEl.appendChild(chip);
        });
    }

    // ============================================================
    // 12. 当前对话搜索
    // ============================================================
    function toggleCurrentSearch() {
        currentSearchRowEl.hidden = !currentSearchRowEl.hidden;
        if (!currentSearchRowEl.hidden) currentSearchEl.focus();
    }

    async function doCurrentSearch() {
        var keyword = (currentSearchEl.value || '').trim();
        state.currentSearchKeyword = keyword;
        if (!keyword) {
            state.filteredMessages = null;
            renderMessages();
            return;
        }

        // 1. 有会话 id 时查服务端，覆盖整个 3 天会话，而不只是页面已加载的 10 轮
        if (state.conversationId) {
            try {
                var res = await request('/conversations/' + encodeURIComponent(state.conversationId)
                    + '/messages/search?keyword=' + encodeURIComponent(keyword) + '&limit=50');
                state.filteredMessages = res.list || [];
                renderMessages();
                return;
            } catch (e) {
                toast(e.message || '搜索失败');
            }
        }

        // 2. 没有会话 id 时退化为本地过滤
        state.filteredMessages = state.messages.filter(function (message) {
            return (message.content || '').toLowerCase().indexOf(keyword.toLowerCase()) >= 0;
        });
        renderMessages();
    }

    function clearCurrentSearch() {
        currentSearchEl.value = '';
        state.currentSearchKeyword = '';
        state.filteredMessages = null;
        renderMessages();
    }

    // ============================================================
    // 13. 历史对话
    // ============================================================
    function openHistory() {
        historyModalEl.hidden = false;
        historyResultsEl.innerHTML = '';
        renderHistoryList();
    }

    function renderHistoryList() {
        if (!historyListEl) return;
        historyListEl.innerHTML = '';
        if (!state.conversations.length) {
            historyListEl.appendChild(el('div', 'gl-ai-empty', '还没有历史对话'));
            return;
        }
        state.conversations.forEach(function (conversation) {
            var item = el('div', 'gl-ai-history-item' + (conversation.id === state.conversationId ? ' gl-ai-active' : ''));
            var main = el('div', 'gl-ai-history-main');
            main.appendChild(el('div', 'gl-ai-history-title', conversation.title || '新对话'));
            main.appendChild(el('div', 'gl-ai-history-meta',
                (conversation.messageCount || 0) + ' 条 · ' + formatTime(conversation.updatedAt)));
            item.appendChild(main);

            var del = el('button', 'gl-ai-del', '×');
            del.title = '删除这个对话';
            del.addEventListener('click', function (event) {
                event.stopPropagation();
                deleteConversation(conversation.id);
            });
            item.appendChild(del);

            item.addEventListener('click', function () {
                openConversation(conversation.id).catch(function (e) { toast(e.message); });
            });
            historyListEl.appendChild(item);
        });
    }

    async function openConversation(id) {
        // 1. 切换会话前先停掉上一轮生成，避免旧回答写进新页面
        if (state.busy) {
            await stopStream();
        }
        state.conversationId = id;
        state.filteredMessages = null;
        state.currentSearchKeyword = '';
        historyModalEl.hidden = true;
        await loadMessages();
        startImageTaskPolling();
        renderHistoryList();
    }

    async function deleteConversation(id) {
        try {
            // 1. 删除当前会话时先停止生成
            if (state.conversationId === id && state.busy) {
                await stopStream();
            }
            await request('/conversations/' + encodeURIComponent(id), { method: 'DELETE' });
            state.conversations = state.conversations.filter(function (c) { return c.id !== id; });
            if (state.conversationId === id) {
                state.conversationId = null;
                if (state.conversations.length) {
                    state.conversationId = state.conversations[0].id;
                    await loadMessages();
                } else {
                    await createConversation();
                }
            }
            renderHistoryList();
            toast('已删除');
        } catch (e) {
            toast(e.message);
        }
    }

    async function searchAllHistory() {
        var keyword = (historySearchEl.value || '').trim();
        if (!keyword) {
            historyResultsEl.innerHTML = '';
            return;
        }
        try {
            var res = await request('/conversations/search?keyword=' + encodeURIComponent(keyword) + '&limit=30');
            var list = res.list || [];
            historyResultsEl.innerHTML = '';
            if (!list.length) {
                historyResultsEl.appendChild(el('div', 'gl-ai-empty', '没有找到包含该关键词的消息'));
                return;
            }
            list.forEach(function (row) {
                var item = el('div', 'gl-ai-search-result');
                item.innerHTML = '<small>' + escapeHtml(row.conversationTitle || '对话') + '</small>'
                    + escapeHtml(row.message ? row.message.content : '');
                item.addEventListener('click', function () {
                    openConversation(row.conversationId).catch(function (e) { toast(e.message); });
                });
                historyResultsEl.appendChild(item);
            });
        } catch (e) {
            toast(e.message);
        }
    }

    // ============================================================
    // 14. 设置
    // ============================================================
    function openSettings() {
        settingsModalEl.hidden = false;
        syncSettingsForm();
        renderCustomModels();
    }

    function syncSettingsForm() {
        if (!panelEl) return;
        colorPickerEl.value = state.themeColor;
        customPromptEl.value = state.settings.customPrompt || '';
        defaultStreamEl.checked = state.streaming;
        if (state.modelId) defaultModelEl.value = state.modelId;
        applyTheme();
    }

    async function saveSettings() {
        var body = {
            themeColor: state.themeColor,
            streamEnabled: defaultStreamEl.checked,
            defaultModel: defaultModelEl.value,
            customPrompt: customPromptEl.value
        };
        try {
            await request('/settings', { method: 'PUT', body: JSON.stringify(body) });
            state.streaming = body.streamEnabled;
            state.modelId = body.defaultModel;
            state.settings.customPrompt = body.customPrompt;
            streamCheckEl.checked = state.streaming;
            modelSelectEl.value = state.modelId;
            applyPetVisibility();
            toast('设置已保存');
        } catch (e) {
            toast(e.message);
        }
    }

    function saveSettingsSilent() {
        // 1. 切换模型/流式时静默保存，失败不打扰
        if (!state.loggedIn) return;
        request('/settings', {
            method: 'PUT',
            body: JSON.stringify({
                streamEnabled: state.streaming,
                defaultModel: state.modelId
            })
        }).catch(function () {});
    }

    function applyPetVisibility() {
        // 1. 宠物是打开聊天窗的唯一入口，必须始终显示；
        //    不再提供“隐藏宠物”设置，避免用户把聊天入口关掉后找不回来
        petEl.style.display = 'block';
    }

    function renderCustomModels() {
        customListEl.innerHTML = '';
        if (!state.customModels.length) {
            customListEl.appendChild(el('div', 'gl-ai-empty', '还没有自定义模型'));
            return;
        }
        state.customModels.forEach(function (model) {
            var item = el('div', 'gl-ai-custom-item');
            var name = el('div', 'gl-ai-custom-name');
            name.textContent = model.displayName + ' · ' + model.modelName;
            var meta = el('small', null, model.apiKeyMask || '');
            name.appendChild(document.createElement('br'));
            name.appendChild(meta);
            item.appendChild(name);
            var del = el('button', 'gl-ai-del', '×');
            del.title = '删除模型';
            del.addEventListener('click', function () { deleteCustomModel(model.id); });
            item.appendChild(del);
            customListEl.appendChild(item);
        });
    }

    async function addCustomModel() {
        var body = {
            displayName: panelEl.querySelector('[data-role="cmName"]').value.trim(),
            modelName: panelEl.querySelector('[data-role="cmModel"]').value.trim(),
            baseUrl: panelEl.querySelector('[data-role="cmBase"]').value.trim(),
            apiKey: panelEl.querySelector('[data-role="cmKey"]').value.trim(),
            useSharedKey: panelEl.querySelector('[data-role="cmShared"]').checked,
            supportsVision: panelEl.querySelector('[data-role="cmVision"]').checked
        };
        if (!body.displayName || !body.modelName || !body.baseUrl) {
            toast('显示名称、modelName、baseUrl 必填');
            return;
        }
        try {
            await request('/settings/models', { method: 'POST', body: JSON.stringify(body) });
            panelEl.querySelector('[data-role="cmName"]').value = '';
            panelEl.querySelector('[data-role="cmModel"]').value = '';
            panelEl.querySelector('[data-role="cmBase"]').value = '';
            panelEl.querySelector('[data-role="cmKey"]').value = '';
            await loadSettings();
            await loadModels();
            renderCustomModels();
            toast('模型已添加');
        } catch (e) {
            toast(e.message);
        }
    }

    async function deleteCustomModel(id) {
        try {
            await request('/settings/models/' + id, { method: 'DELETE' });
            await loadSettings();
            await loadModels();
            renderCustomModels();
            toast('模型已删除');
        } catch (e) {
            toast(e.message);
        }
    }

    // ============================================================
    // 15. 登录态与初始化
    // ============================================================
    async function checkLogin() {
        try {
            var res = await request('/auth/me');
            state.loggedIn = true;
            state.me = res.data;
            state.loginError = null;
        } catch (e) {
            // 1. 只有明确的“未登录”才把用户踢回登录提示；
            //    服务刚启动/网络抖动（99000、断网）时保留原状态，避免假故障
            if (e && (e.code === 15001 || e.code === 15012)) {
                state.loggedIn = false;
                state.me = null;
                state.loginError = null;
            } else {
                state.loginError = (e && e.message) ? e.message : 'AI 服务连接失败';
            }
        }
    }

    async function manualRecheckLogin() {
        // 1. 用户在登录页完成登录后，手动触发一次同步
        await checkLogin();
        if (state.loggedIn) {
            showLoginMask(false);
            if (!state.models.length) {
                await loadAll();
            }
            toast('登录状态已同步');
            return;
        }
        toast(state.loginError || '仍未检测到登录状态，请先完成登录');
    }

    function recheckLogin() {
        // 1. 用户可能在别的标签页/登录页完成登录，回到本页时重新确认
        if (!panelEl || panelEl.hidden || state.loggedIn) {
            return;
        }
        checkLogin().then(function () {
            if (state.loggedIn) {
                showLoginMask(false);
                loadAll();
            }
        });
    }

    async function init() {
        // 1. 注入样式、构建 DOM
        injectCss();
        buildRoot();
        buildPet();
        buildPanel();
        applyPetVisibility();

        // 2. 恢复本地主题（服务端设置加载后会覆盖）
        try {
            var localTheme = localStorage.getItem(THEME_STORAGE);
            if (localTheme) state.themeColor = localTheme;
        } catch (e) { /* ignore */ }
        applyTheme();

        // 3. 检查登录态；已登录才加载模型/会话
        await checkLogin();
        if (state.loggedIn) {
            await loadAll();
            streamCheckEl.checked = state.streaming;
        } else {
            showLoginMask(true);
        }

        // 4. 窗口大小变化时把宠物拉回可视区
        window.addEventListener('resize', function () {
            applyPetSize();
            setPetPosition(parseFloat(petEl.style.left || 0), parseFloat(petEl.style.top || 0));
            // 1. 窗口变小后重新夹取聊天框，避免它超出可视区
            if (panelEl && !panelEl.hidden) {
                applyPanelSize(panelEl.offsetWidth, panelEl.offsetHeight);
            }
        });

        // 5. 从登录页回来/切换标签页时重新检查登录态
        window.addEventListener('focus', recheckLogin);
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden) recheckLogin();
        });

        // 6. 样式/图片加载完后再量一次尺寸，避免首屏 CSS 没加载时算错精灵图大小
        window.addEventListener('load', function () {
            applyPetSize();
            setPetPosition(parseFloat(petEl.style.left || 0), parseFloat(petEl.style.top || 0));
        });
    }

    // 暴露少量调试入口
    window.glAiWidget = {
        open: openPanel,
        close: closePanel,
        state: state
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
