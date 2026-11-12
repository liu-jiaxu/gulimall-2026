/* ============================================================
 * gulimall-k8s AI 对话页面
 * ------------------------------------------------------------
 * 原生 JS，无框架。
 * 登录/注册/改密、会话列表、模型选择、SSE 流式聊天。
 * ============================================================ */
(function () {
    'use strict';

    // 1. 页面状态
    const state = {
        user: null,
        models: [],
        defaultModel: null,
        conversations: [],
        conversationId: null,
        busy: false,
        mode: 'login',
        loginAt: null,
        sessionTimer: null
    };

    // 2. DOM 引用
    const $ = function (id) {
        return document.getElementById(id);
    };

    const els = {
        authView: $('auth-view'),
        chatView: $('chat-view'),
        authForm: $('auth-form'),
        authUsername: $('auth-username'),
        authPassword: $('auth-password'),
        authInviteCode: $('auth-invite-code'),
        authDisplayName: $('auth-display-name'),
        displayNameRow: $('display-name-row'),
        authError: $('auth-error'),
        authSubmit: $('auth-submit'),
        tabs: document.querySelectorAll('.tab'),
        forgotLink: $('forgot-link'),
        resetModal: $('reset-modal'),
        resetUsername: $('reset-username'),
        resetPassword: $('reset-password'),
        resetPassword2: $('reset-password2'),
        resetError: $('reset-error'),
        resetCancel: $('reset-cancel'),
        resetSubmit: $('reset-submit'),
        conversationList: $('conversation-list'),
        newChat: $('new-chat'),
        currentUser: $('current-user'),
        logout: $('logout'),
        modelSelect: $('model-select'),
        chatStatus: $('chat-status'),
        messageList: $('message-list'),
        chatForm: $('chat-form'),
        messageInput: $('message-input'),
        sendButton: $('send-button'),
        toast: $('toast')
    };

    let toastTimer = null;

    // 3. 通用请求封装
    async function api(path, options) {
        const config = Object.assign({
            method: 'GET',
            credentials: 'same-origin',
            headers: {}
        }, options || {});

        // 1. 有请求体时默认按 JSON 发送
        if (config.body && !config.headers['Content-Type']) {
            config.headers['Content-Type'] = 'application/json';
        }

        const response = await fetch(path, config);

        // 2. 尽量解析 JSON；不是 JSON 时保留原始文本
        const text = await response.text();
        let data = null;
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (e) {
                data = { message: text };
            }
        }

        // 3. 非 2xx 统一抛错；401 直接回登录页
        if (!response.ok) {
            if (response.status === 401) {
                showAuth();
            }
            throw new Error((data && data.message) || ('请求失败（' + response.status + '）'));
        }
        return data || {};
    }

    // 4. 顶部提示
    function toast(message) {
        els.toast.textContent = message;
        els.toast.classList.remove('hidden');
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            els.toast.classList.add('hidden');
        }, 2600);
    }

    // ============================================================
    // 登录 / 注册 / 改密
    // ============================================================

    function showAuth() {
        // 1. 清理登录态和 12 小时计时器
        clearTimeout(state.sessionTimer);
        state.sessionTimer = null;
        state.loginAt = null;
        state.user = null;
        state.conversationId = null;
        state.conversations = [];

        // 2. 回到登录页，邀请码每次都要重新输入
        els.chatView.classList.add('hidden');
        els.authView.classList.remove('hidden');
        els.authPassword.value = '';
        els.authInviteCode.value = '';
        els.authError.classList.add('hidden');
        if (els.authUsername.value.trim()) {
            els.authPassword.focus();
        } else {
            els.authUsername.focus();
        }
    }

    function showChat() {
        els.authView.classList.add('hidden');
        els.chatView.classList.remove('hidden');
    }

    function startSessionTimer(loginAt) {
        // 1. 先清掉旧计时器，避免重复登录叠加多个定时器
        clearTimeout(state.sessionTimer);
        state.sessionTimer = null;

        // 2. 服务端是绝对 12 小时，这里按登录时间计算剩余毫秒
        const expiresAt = (loginAt || Date.now()) + 12 * 60 * 60 * 1000;
        const remaining = expiresAt - Date.now();
        if (remaining <= 0) {
            showAuth();
            toast('登录已过期，请重新登录');
            return;
        }

        // 3. 到点后直接回登录页；之后任何接口 401 也会回登录页
        state.sessionTimer = setTimeout(function () {
            showAuth();
            toast('登录已过期，请重新登录');
        }, remaining);
    }

    function setMode(mode) {
        state.mode = mode;
        els.tabs.forEach(function (tab) {
            tab.classList.toggle('active', tab.dataset.mode === mode);
        });
        els.displayNameRow.classList.toggle('hidden', mode !== 'register');
        els.authSubmit.textContent = mode === 'register' ? '注册' : '登录';
        els.authPassword.autocomplete = mode === 'register' ? 'new-password' : 'current-password';
        els.authError.classList.add('hidden');
    }

    async function submitAuth(event) {
        event.preventDefault();

        // 1. 基础校验：邀请码每次登录/注册都必须输入
        const username = els.authUsername.value.trim();
        const password = els.authPassword.value;
        const inviteCode = els.authInviteCode.value.trim();
        if (!username || !password || !inviteCode) {
            showAuthError('请填写用户名、密码和邀请码');
            return;
        }

        // 2. 按当前模式调登录或注册接口，邀请码放在请求体里，不会存进 Session
        els.authSubmit.disabled = true;
        try {
            const path = state.mode === 'register' ? '/api/auth/register' : '/api/auth/login';
            const body = {
                username: username,
                password: password,
                inviteCode: inviteCode
            };
            if (state.mode === 'register') {
                body.displayName = els.authDisplayName.value.trim();
            }
            const data = await api(path, {
                method: 'POST',
                body: JSON.stringify(body)
            });

            // 3. 登录/注册成功直接进入聊天页，记录登录时间
            state.user = data.user;
            state.loginAt = data.loginAt || Date.now();
            await enterChat();
        } catch (err) {
            showAuthError(err.message);
        } finally {
            els.authSubmit.disabled = false;
        }
    }

    function showAuthError(message) {
        els.authError.textContent = message;
        els.authError.classList.remove('hidden');
    }

    function openResetModal() {
        els.resetModal.classList.remove('hidden');
        els.resetUsername.value = els.authUsername.value.trim();
        els.resetPassword.value = '';
        els.resetPassword2.value = '';
        els.resetError.classList.add('hidden');
        els.resetUsername.focus();
    }

    function closeResetModal() {
        els.resetModal.classList.add('hidden');
    }

    async function submitReset() {
        // 1. 前端先做基本校验
        const username = els.resetUsername.value.trim();
        const password = els.resetPassword.value;
        const password2 = els.resetPassword2.value;
        if (!username || !password) {
            showResetError('请填写用户名和新密码');
            return;
        }
        if (password !== password2) {
            showResetError('两次输入的密码不一致');
            return;
        }

        // 2. 直接调改密接口，没有验证码
        els.resetSubmit.disabled = true;
        try {
            await api('/api/auth/reset-password', {
                method: 'POST',
                body: JSON.stringify({ username: username, newPassword: password })
            });
            closeResetModal();
            toast('密码已修改，请用新密码登录');
            els.authUsername.value = username;
            els.authPassword.value = '';
            els.authPassword.focus();
        } catch (err) {
            showResetError(err.message);
        } finally {
            els.resetSubmit.disabled = false;
        }
    }

    function showResetError(message) {
        els.resetError.textContent = message;
        els.resetError.classList.remove('hidden');
    }

    async function logout() {
        try {
            await api('/api/auth/logout', { method: 'POST' });
        } catch (e) {
            // 会话已经失效时也直接回登录页
        }
        showAuth();
    }

    // ============================================================
    // 进入聊天页
    // ============================================================

    async function enterChat() {
        showChat();
        els.currentUser.textContent = state.user
            ? (state.user.displayName || state.user.username)
            : '';

        // 0. 启动 12 小时登录态计时，过期自动回登录页
        startSessionTimer(state.loginAt);

        // 1. 加载模型和会话列表
        await loadModels();
        await loadConversations();

        // 2. 有历史就打开最近一个，没有就开新对话
        if (state.conversations.length > 0) {
            await openConversation(state.conversations[0].id);
        } else {
            newChat();
        }
    }

    async function loadModels() {
        // 1. 取模型清单和默认模型
        const data = await api('/api/models');
        state.models = data.models || [];
        state.defaultModel = data.defaultModel || (state.models[0] && state.models[0].id) || '';

        // 2. 填充下拉框
        els.modelSelect.innerHTML = '';
        state.models.forEach(function (model) {
            const option = document.createElement('option');
            option.value = model.id;
            option.textContent = model.displayName || model.id;
            els.modelSelect.appendChild(option);
        });
        if (state.defaultModel) {
            els.modelSelect.value = state.defaultModel;
        }
    }

    async function loadConversations() {
        // 1. 取最近 3 天会话
        const data = await api('/api/conversations');
        state.conversations = data.list || [];
        renderConversations();
    }

    function renderConversations() {
        els.conversationList.innerHTML = '';

        state.conversations.forEach(function (conv) {
            const item = document.createElement('div');
            item.className = 'conversation-item' + (conv.id === state.conversationId ? ' active' : '');
            item.dataset.id = conv.id;

            const main = document.createElement('div');
            main.className = 'conversation-main';

            const title = document.createElement('div');
            title.className = 'conversation-title';
            title.textContent = conv.title || '新对话';

            const time = document.createElement('div');
            time.className = 'conversation-time';
            time.textContent = formatTime(conv.updatedAt);

            main.appendChild(title);
            main.appendChild(time);

            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'delete-conv';
            del.title = '删除会话';
            del.textContent = '×';
            del.addEventListener('click', function (event) {
                event.stopPropagation();
                deleteConversation(conv.id);
            });

            item.appendChild(main);
            item.appendChild(del);
            item.addEventListener('click', function () {
                openConversation(conv.id);
            });
            els.conversationList.appendChild(item);
        });
    }

    async function openConversation(id) {
        if (state.busy) {
            return;
        }

        // 1. 先标记当前会话
        state.conversationId = id;
        renderConversations();

        // 2. 会话有模型时同步下拉框
        const conv = state.conversations.find(function (item) {
            return item.id === id;
        });
        if (conv && conv.modelId && state.models.some(function (model) {
            return model.id === conv.modelId;
        })) {
            els.modelSelect.value = conv.modelId;
        }

        // 3. 读取最近 50 条消息
        const data = await api('/api/conversations/' + encodeURIComponent(id) + '/messages');
        renderMessages(data.list || []);
    }

    function newChat() {
        if (state.busy) {
            return;
        }

        // 1. 清空当前会话，下一条消息会自动创建
        state.conversationId = null;
        renderConversations();
        els.modelSelect.value = state.defaultModel || (state.models[0] && state.models[0].id) || '';

        // 2. 清空消息区
        renderMessages([]);
        els.messageInput.focus();
    }

    async function deleteConversation(id) {
        if (state.busy) {
            return;
        }
        if (!window.confirm('删除这个会话？')) {
            return;
        }

        try {
            await api('/api/conversations/' + encodeURIComponent(id), { method: 'DELETE' });

            // 1. 删的是当前会话时回到空白状态
            if (state.conversationId === id) {
                state.conversationId = null;
                renderMessages([]);
            }

            // 2. 刷新列表
            await loadConversations();
            toast('会话已删除');
        } catch (err) {
            toast(err.message);
        }
    }

    // ============================================================
    // 消息渲染
    // ============================================================

    function renderMessages(messages) {
        els.messageList.innerHTML = '';

        // 1. 没有消息时显示空状态
        if (!messages || messages.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-state';
            empty.innerHTML = '<h2>开始一段新对话</h2><p>输入问题，AI 会保留最近 3 天的会话记录。</p>';
            els.messageList.appendChild(empty);
            return;
        }

        // 2. 按顺序渲染历史消息
        messages.forEach(function (message) {
            appendMessage(
                message.role,
                message.content,
                message.modelId,
                message.inputTokens,
                message.outputTokens
            );
        });
        scrollToBottom();
    }

    function appendMessage(role, content, modelId, inputTokens, outputTokens) {
        // 1. 有真实消息时先移除空状态
        const empty = els.messageList.querySelector('.empty-state');
        if (empty) {
            empty.remove();
        }

        // 2. 外层气泡
        const wrapper = document.createElement('div');
        wrapper.className = 'message ' + (role === 'user' ? 'user' : 'assistant');

        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = content || '';
        wrapper.appendChild(bubble);

        // 3. AI 消息显示模型和 token
        if (role === 'assistant' && modelId) {
            setAssistantMeta(wrapper, modelId, inputTokens, outputTokens);
        }

        els.messageList.appendChild(wrapper);
        return wrapper;
    }

    function setAssistantMeta(wrapper, modelId, inputTokens, outputTokens) {
        // 1. 已有 meta 时先删掉，避免重复
        const old = wrapper.querySelector('.message-meta');
        if (old) {
            old.remove();
        }

        // 2. 模型名 + token 用量
        let text = modelId || '';
        if (inputTokens != null || outputTokens != null) {
            text += ' · '
                + (inputTokens == null ? '-' : inputTokens) + ' in / '
                + (outputTokens == null ? '-' : outputTokens) + ' out';
        }
        if (!text) {
            return;
        }

        const meta = document.createElement('div');
        meta.className = 'message-meta';
        meta.textContent = text;
        wrapper.appendChild(meta);
    }

    function scrollToBottom() {
        els.messageList.scrollTop = els.messageList.scrollHeight;
    }

    function formatTime(timestamp) {
        if (!timestamp) {
            return '';
        }
        const date = new Date(timestamp);
        const now = new Date();
        const pad = function (n) {
            return String(n).padStart(2, '0');
        };
        if (date.toDateString() === now.toDateString()) {
            return pad(date.getHours()) + ':' + pad(date.getMinutes());
        }
        return (date.getMonth() + 1) + '-' + date.getDate() + ' '
            + pad(date.getHours()) + ':' + pad(date.getMinutes());
    }

    // ============================================================
    // 发送消息与 SSE 解析
    // ============================================================

    async function sendMessage(event) {
        event.preventDefault();
        if (state.busy) {
            return;
        }

        // 1. 取输入内容
        const message = els.messageInput.value.trim();
        if (!message) {
            return;
        }

        // 2. 锁定发送按钮和输入框
        state.busy = true;
        els.sendButton.disabled = true;
        els.sendButton.textContent = '发送中';
        els.chatStatus.textContent = 'AI 正在思考…';
        els.messageInput.value = '';
        els.messageInput.style.height = 'auto';

        // 3. 先画用户气泡，再画 AI 占位气泡
        appendMessage('user', message, null, null, null);
        const assistantWrapper = appendMessage('assistant', '正在思考…', null, null, null);
        const bubble = assistantWrapper.querySelector('.bubble');
        bubble.classList.add('pending');
        scrollToBottom();

        let streamOk = false;
        try {
            await streamChat(message, assistantWrapper, bubble);
            streamOk = true;
        } catch (err) {
            bubble.classList.remove('pending');
            bubble.classList.add('error');
            bubble.textContent = err.message || '请求失败';
        } finally {
            state.busy = false;
            els.sendButton.disabled = false;
            els.sendButton.textContent = '发送';
            els.chatStatus.textContent = '';
            scrollToBottom();
        }

        // 4. 回答成功后刷新会话列表；列表失败不影响已经拿到的回答
        if (streamOk) {
            try {
                await loadConversations();
            } catch (err) {
                toast(err.message);
            }
        }
    }

    async function streamChat(message, assistantWrapper, bubble) {
        // 1. 用 fetch 发 POST，自己解析 SSE；这样能带请求体和 Cookie
        const response = await fetch('/api/chat/stream', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                conversationId: state.conversationId,
                modelId: els.modelSelect.value,
                message: message
            })
        });

        // 2. 非 2xx 时按普通 JSON 错误处理
        if (!response.ok) {
            if (response.status === 401) {
                showAuth();
            }
            const text = await response.text();
            let errorText = text;
            try {
                const data = JSON.parse(text);
                errorText = data.message || text;
            } catch (e) {
                // 不是 JSON 就用原始文本
            }
            throw new Error(errorText || ('请求失败（' + response.status + '）'));
        }

        // 3. 逐块读取 SSE
        const reader = response.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        let answer = '';
        let finished = false;
        let errorMessage = null;

        while (true) {
            const result = await reader.read();
            if (result.done) {
                break;
            }

            // 1. 统一换行，兼容 \r\n\r\n 和 \n\n
            buffer = (buffer + decoder.decode(result.value, { stream: true }))
                .replace(/\r\n/g, '\n');

            // 2. 一个 SSE 事件以空行结束
            let index;
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                const block = buffer.slice(0, index);
                buffer = buffer.slice(index + 2);
                const event = parseSseBlock(block);

                if (event.name === 'meta') {
                    const data = safeJson(event.data);
                    if (data && data.conversationId) {
                        state.conversationId = data.conversationId;
                    }
                } else if (event.name === 'delta') {
                    const data = safeJson(event.data);
                    if (data && typeof data.content === 'string') {
                        if (bubble.classList.contains('pending')) {
                            bubble.classList.remove('pending');
                            bubble.textContent = '';
                        }
                        answer += data.content;
                        bubble.textContent = answer;
                        scrollToBottom();
                    }
                } else if (event.name === 'done') {
                    finished = true;
                    const data = safeJson(event.data) || {};
                    bubble.classList.remove('pending');
                    if (!answer && data.content) {
                        bubble.textContent = data.content;
                    }
                    setAssistantMeta(
                        assistantWrapper,
                        data.modelId || els.modelSelect.value,
                        data.inputTokens,
                        data.outputTokens
                    );
                } else if (event.name === 'error') {
                    finished = true;
                    const data = safeJson(event.data) || {};
                    errorMessage = data.message || 'AI 调用失败';
                }
            }
        }

        // 4. 服务端明确报错时抛出
        if (errorMessage) {
            throw new Error(errorMessage);
        }

        // 5. 连接断了但一个 token 都没收到，按失败处理
        if (!finished && !answer) {
            throw new Error('连接中断，没有收到 AI 回复');
        }
    }

    function parseSseBlock(block) {
        let name = 'message';
        const dataLines = [];

        block.split(/\r?\n/).forEach(function (line) {
            if (line.startsWith('event:')) {
                name = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
                dataLines.push(line.slice(5).replace(/^ /, ''));
            }
        });
        return { name: name, data: dataLines.join('\n') };
    }

    function safeJson(text) {
        try {
            return JSON.parse(text);
        } catch (e) {
            return null;
        }
    }

    // ============================================================
    // 事件绑定与启动
    // ============================================================

    function bindEvents() {
        // 1. 登录/注册
        els.tabs.forEach(function (tab) {
            tab.addEventListener('click', function () {
                setMode(tab.dataset.mode);
            });
        });
        els.authForm.addEventListener('submit', submitAuth);

        // 2. 忘记密码
        els.forgotLink.addEventListener('click', openResetModal);
        els.resetCancel.addEventListener('click', closeResetModal);
        els.resetSubmit.addEventListener('click', submitReset);
        els.resetModal.addEventListener('click', function (event) {
            if (event.target === els.resetModal) {
                closeResetModal();
            }
        });

        // 3. 聊天
        els.newChat.addEventListener('click', newChat);
        els.logout.addEventListener('click', logout);
        els.chatForm.addEventListener('submit', sendMessage);

        // 4. Enter 发送，Shift+Enter 换行
        els.messageInput.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                els.chatForm.requestSubmit();
            }
        });

        // 5. 输入框随内容自动变高
        els.messageInput.addEventListener('input', function () {
            els.messageInput.style.height = 'auto';
            els.messageInput.style.height = Math.min(els.messageInput.scrollHeight, 160) + 'px';
        });
    }

    async function init() {
        bindEvents();
        setMode('login');

        // 1. 页面加载时先看 Session 是否还有效
        try {
            const data = await api('/api/auth/me');
            state.user = data.user;
            state.loginAt = data.loginAt || Date.now();
            await enterChat();
        } catch (err) {
            showAuth();
        }
    }

    document.addEventListener('DOMContentLoaded', init);
})();
