<template>
  <div class="ai-chat-page" :style="{ '--ai-primary': themeColor }">
    <!-- 左侧：会话列表 -->
    <aside class="ai-side">
      <div class="ai-side-head">
        <div>
          <div class="ai-side-title">AI 助手</div>
          <div class="ai-side-sub">只读查询 · 运营分析</div>
        </div>
        <el-button size="mini" type="primary" icon="el-icon-plus" @click="newConversation">新对话</el-button>
      </div>

      <el-input
        v-model="historyKeyword"
        size="small"
        placeholder="搜索 3 天内全部对话"
        clearable
        @keyup.enter.native="searchHistory">
        <el-button slot="append" icon="el-icon-search" @click="searchHistory"></el-button>
      </el-input>

      <div class="ai-session-list">
        <div
          v-for="c in conversations"
          :key="c.id"
          :class="['ai-session', { active: c.id === conversationId }]"
          @click="openConversation(c.id)">
          <div class="ai-session-title">{{ c.title || '新对话' }}</div>
          <div class="ai-session-meta">{{ c.messageCount || 0 }} 条 · {{ formatTime(c.updatedAt) }}</div>
          <i class="el-icon-delete ai-session-del" title="删除对话" @click.stop="deleteConversation(c.id)"></i>
        </div>
        <div v-if="!conversations.length" class="ai-empty">还没有历史对话</div>
      </div>

      <div v-if="historyResults.length" class="ai-history-results">
        <div class="ai-results-title">3 天内搜索结果</div>
        <div
          v-for="(r, index) in historyResults"
          :key="index"
          class="ai-result-item"
          @click="openConversation(r.conversationId)">
          <small>{{ r.conversationTitle }}</small>
          <span>{{ r.message && r.message.content }}</span>
        </div>
      </div>
    </aside>

    <!-- 右侧：聊天区 -->
    <section class="ai-main">
      <header class="ai-main-head">
        <div class="ai-main-title">
          <div class="ai-main-title-main">运营助手</div>
          <span>商品 / 订单 / 库存 / 会员 / 秒杀</span>
        </div>

        <el-select
          v-model="modelId"
          class="ai-model-select"
          size="small"
          style="width: 220px"
          @change="saveSettingsSilent">
          <el-option
            v-for="m in models"
            :key="m.id"
            :label="m.displayName + (m.custom ? '（自定义）' : '') + (m.tools ? '' : ' · 不支持查库')"
            :value="m.id">
          </el-option>
        </el-select>

        <el-switch v-model="streaming" active-text="流式" @change="saveSettingsSilent"></el-switch>

        <el-input
          v-model="currentKeyword"
          class="ai-search-input"
          size="small"
          placeholder="搜索当前对话"
          clearable
          style="width: 180px">
        </el-input>

        <el-button
          class="ai-settings-btn"
          size="mini"
          icon="el-icon-setting"
          @click="openSettings">设置</el-button>
      </header>

      <div class="ai-messages" ref="messages">
        <div v-if="!visibleMessages.length" class="ai-empty">
          <div class="ai-empty-title">你好，我是谷粒商城运营助手</div>
          <div>可以问我：今天交易额、某订单状态、某 SKU 库存、商品分类、秒杀场次、会员概要……</div>
          <div class="ai-empty-tip">AI 只做查询，不会修改订单/商品数据。</div>
        </div>

        <div v-for="m in visibleMessages" :key="m.id || m.createdAt" :class="['ai-msg', m.role]">
          <div class="ai-avatar">{{ m.role === 'user' ? '我' : 'AI' }}</div>
          <div class="ai-bubble">
            <div v-if="m.imageUrls && m.imageUrls.length" class="ai-images">
              <img
                v-for="url in m.imageUrls"
                :key="url"
                :src="url"
                alt="上传图片"
                @click="preview(url)">
            </div>
            <div class="ai-content" v-html="md(m.content)"></div>
            <span v-if="m.streaming" class="ai-typing"><i></i><i></i><i></i></span>
            <div class="ai-time">
              {{ formatTime(m.createdAt) }}
              <span v-if="m.outputTokens"> · {{ m.outputTokens }} tokens</span>
            </div>
          </div>
        </div>
      </div>

      <div class="ai-composer">
        <div v-if="pendingImages.length" class="ai-pending">
          <div v-for="(img, index) in pendingImages" :key="img.imageId" class="ai-chip">
            <img :src="img.url" alt="待发送图片">
            <i class="el-icon-close" @click="pendingImages.splice(index, 1)"></i>
          </div>
        </div>

        <el-input
          ref="inputBox"
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行；↑ 回显上一问；可直接 Ctrl+V 粘贴图片"
          @input="resetRecall"
          @keydown.native="onKeydown"
          @paste.native="handlePaste">
        </el-input>

        <div class="ai-actions">
          <input
            ref="file"
            type="file"
            accept="image/jpeg,image/png,image/webp"
            style="display: none"
            @change="uploadImage">
          <el-button size="small" icon="el-icon-picture-outline" @click="$refs.file.click()">图片</el-button>
          <el-button v-if="busy || hasStreaming" size="small" type="danger" plain @click="stopStream">停止</el-button>
          <el-button size="small" type="primary" :loading="busy" @click="send">发送</el-button>
        </div>
      </div>
    </section>

    <!-- 设置 -->
    <el-dialog title="AI 设置" :visible.sync="settingsVisible" width="560px" append-to-body>
      <el-form label-width="100px" size="small">
        <el-form-item label="窗口颜色">
          <el-color-picker v-model="themeColor"></el-color-picker>
        </el-form-item>
        <el-form-item label="默认模型">
          <el-select v-model="defaultModel" style="width: 100%">
            <el-option
              v-for="m in models"
              :key="m.id"
              :label="m.displayName + (m.custom ? '（自定义）' : '') + (m.tools ? '' : ' · 不支持查库')"
              :value="m.id">
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="流式输出">
          <el-switch v-model="settingsStream"></el-switch>
        </el-form-item>
        <el-form-item label="自定义提示词">
          <el-input
            v-model="customPrompt"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="只影响语气和表达，不能改变权限规则">
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveSettings">保存设置</el-button>
        </el-form-item>
      </el-form>

      <div class="ai-divider">我的自定义模型</div>

      <div class="ai-custom-list">
        <div v-for="model in customModels" :key="model.id" class="ai-custom-item">
          <div class="ai-custom-name">
            {{ model.displayName }}
            <small>{{ model.modelName }} · {{ model.apiKeyMask }}</small>
          </div>
          <el-button type="text" style="color: #e4393c" @click="deleteCustomModel(model.id)">删除</el-button>
        </div>
        <div v-if="!customModels.length" class="ai-empty">还没有自定义模型</div>
      </div>

      <div class="ai-divider">添加自定义模型</div>

      <el-form label-width="100px" size="small">
        <el-form-item label="显示名称">
          <el-input v-model="customForm.displayName" placeholder="例如 我的 GPT"></el-input>
        </el-form-item>
        <el-form-item label="modelName">
          <el-input v-model="customForm.modelName" placeholder="例如 gpt-4o-mini"></el-input>
        </el-form-item>
        <el-form-item label="baseUrl">
          <el-input v-model="customForm.baseUrl" placeholder="必须 https://"></el-input>
        </el-form-item>
        <el-form-item label="apiKey">
          <el-input
            v-model="customForm.apiKey"
            :disabled="customForm.useSharedKey"
            placeholder="不会明文保存">
          </el-input>
        </el-form-item>
        <el-form-item label="选项">
          <el-checkbox v-model="customForm.useSharedKey">使用公共 closeai key</el-checkbox>
          <el-checkbox v-model="customForm.supportsVision">支持图片识别</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="addCustomModel">添加模型</el-button>
        </el-form-item>
      </el-form>
    </el-dialog>

    <!-- 图片预览 -->
    <el-dialog :visible.sync="previewVisible" width="760px" append-to-body title="图片预览">
      <img :src="previewUrl" style="max-width: 100%; display: block; margin: 0 auto">
    </el-dialog>
  </div>
</template>

<script>
/* global AbortController */
import Vue from 'vue'
import streamSse from '@/utils/aiSse'

export default {
  data () {
    return {
      models: [],
      customModels: [],
      conversations: [],
      conversationId: null,
      messages: [],
      input: '',
      modelId: null,
      streaming: true,
      themeColor: '#0BB2D4',
      busy: false,
      abortController: null,
      currentKeyword: '',
      historyKeyword: '',
      historyResults: [],
      pendingImages: [],
      settingsVisible: false,
      settingsStream: true,
      defaultModel: null,
      customPrompt: '',
      customForm: {
        displayName: '',
        modelName: '',
        baseUrl: '',
        apiKey: '',
        useSharedKey: false,
        supportsVision: false
      },
      previewVisible: false,
      previewUrl: '',
      // 上方向键回显：当前停在用户消息列表的哪个下标，-1 表示没在回显
      recallIndex: -1,
      // 图片异步生成：轮询任务状态
      imageTaskTimer: null,
      imageTaskPollSeq: 0,
      imageTaskSeen: {},
      imageTaskInited: false,
      imageTaskNoticeShown: false,
      inited: false
    }
  },
  computed: {
    hasStreaming () {
      return this.messages.some((m) => m.streaming)
    },
    visibleMessages () {
      if (!this.currentKeyword) {
        return this.messages
      }
      const needle = this.currentKeyword.toLowerCase()
      return this.messages.filter((m) => (m.content || '').toLowerCase().indexOf(needle) >= 0)
    }
  },
  watch: {
    themeColor (value) {
      this.applyTheme(value)
    }
  },
  created () {
    this.init()
  },
  mounted () {
    this.applyTheme(this.themeColor)
  },
  activated () {
    if (!this.inited) {
      this.init()
    } else {
      this.startImageTaskPolling()
    }
  },
  deactivated () {
    this.stopImageTaskPolling()
  },
  beforeDestroy () {
    this.stopImageTaskPolling()
  },
  methods: {
    applyTheme (color) {
      // 1. Vue 2.5 对内联 CSS 变量的支持不稳定，直接写到根元素上
      if (this.$el && this.$el.style) {
        this.$el.style.setProperty('--ai-primary', color || this.themeColor)
      }
    },
    async init () {
      if (this.inited) {
        return
      }
      try {
        // 1. 管理端页面必须先确认当前 agent 身份是 ADMIN
        await this.ensureAdminPrincipal()
        // 2. 确认是管理员后，再加载设置、模型和会话
        await this.loadSettings()
        await this.loadModels()
        await this.loadConversations()
        this.inited = true
        this.startImageTaskPolling()
      } catch (e) {
        this.$message.error(e.message || 'AI 助手加载失败')
      }
    },
    async ensureAdminPrincipal () {
      // 1. 读取 agent 当前解析到的身份
      let data = await this.get('/agent/auth/me')
      let me = data.data || {}

      // 2. 只有用户 Cookie 时，管理端 Cookie 可能过期；先用 renren token 换一次再查
      if (me.type === 'USER') {
        await this.refreshAgentJwt()
        data = await this.get('/agent/auth/me')
        me = data.data || {}
      }

      // 3. 仍然不是管理员就拒绝加载会话，避免把用户端会话展示到管理端
      if (me.type !== 'ADMIN') {
        const error = new Error('当前登录身份不是管理员，请使用管理端账号登录')
        error.code = 15001
        throw error
      }
    },

    // ---------- HTTP ----------
    async refreshAgentJwt () {
      // 1. 用 renren-fast 旧 token 换取新的 agent JWT，服务端会更新 HttpOnly Cookie
      const { data } = await this.$http({
        url: this.$http.adornUrl('/sys/agent/jwt/refresh'),
        method: 'post'
      })
      if (data.code !== 0) {
        const error = new Error(data.msg || '登录已过期，请重新登录')
        error.code = data.code
        throw error
      }
    },
    async get (path, params, retried) {
      const { data } = await this.$http({
        url: this.$http.adornUrl(path),
        method: 'get',
        params: params || {}
      })
      if ((data.code === 15012 || data.code === 15001) && !retried) {
        await this.refreshAgentJwt()
        return this.get(path, params, true)
      }
      if (data.code !== 0) {
        const error = new Error(data.msg || '请求失败')
        error.code = data.code
        throw error
      }
      return data
    },
    async post (path, body, retried) {
      const { data } = await this.$http({
        url: this.$http.adornUrl(path),
        method: 'post',
        data: body || {}
      })
      if ((data.code === 15012 || data.code === 15001) && !retried) {
        await this.refreshAgentJwt()
        return this.post(path, body, true)
      }
      if (data.code !== 0) {
        const error = new Error(data.msg || '请求失败')
        error.code = data.code
        throw error
      }
      return data
    },
    async put (path, body, retried) {
      const { data } = await this.$http({
        url: this.$http.adornUrl(path),
        method: 'put',
        data: body || {}
      })
      if ((data.code === 15012 || data.code === 15001) && !retried) {
        await this.refreshAgentJwt()
        return this.put(path, body, true)
      }
      if (data.code !== 0) {
        const error = new Error(data.msg || '请求失败')
        error.code = data.code
        throw error
      }
      return data
    },
    async del (path, retried) {
      const { data } = await this.$http({
        url: this.$http.adornUrl(path),
        method: 'delete'
      })
      if ((data.code === 15012 || data.code === 15001) && !retried) {
        await this.refreshAgentJwt()
        return this.del(path, true)
      }
      if (data.code !== 0) {
        const error = new Error(data.msg || '请求失败')
        error.code = data.code
        throw error
      }
      return data
    },

    // ---------- 数据加载 ----------
    async loadSettings () {
      const data = await this.get('/agent/settings')
      const setting = data.setting || {}
      this.customModels = data.customModels || []
      this.themeColor = setting.themeColor || '#0BB2D4'
      this.$nextTick(() => this.applyTheme(this.themeColor))
      this.streaming = setting.streamEnabled !== false
      this.settingsStream = this.streaming
      this.customPrompt = setting.customPrompt || ''
      this.modelId = setting.defaultModel || this.modelId
      this.defaultModel = this.modelId
    },
    async loadModels () {
      const data = await this.get('/agent/models')
      this.models = data.models || []
      if (!this.modelId && this.models.length) {
        this.modelId = this.models[0].id
      }
      if (!this.defaultModel) {
        this.defaultModel = this.modelId
      }
    },
    async loadConversations () {
      const data = await this.get('/agent/conversations')
      this.conversations = data.list || []
      if (!this.conversations.length) {
        await this.createConversation()
        return
      }
      if (!this.conversationId || !this.conversations.some((c) => c.id === this.conversationId)) {
        this.conversationId = this.conversations[0].id
      }
      await this.loadMessages()
    },
    async loadMessages () {
      if (!this.conversationId) {
        return
      }
      const data = await this.get('/agent/conversations/' + encodeURIComponent(this.conversationId) + '/messages', { limit: 20 })
      this.messages = data.list || []
      this.recallIndex = -1
      this.$nextTick(this.scrollBottom)
    },
    async refreshConversations () {
      try {
        const data = await this.get('/agent/conversations')
        this.conversations = data.list || []
      } catch (e) {
        console.warn(e)
      }
    },

    // ---------- 会话 ----------
    async createConversation () {
      const data = await this.post('/agent/conversations', { modelId: this.modelId })
      this.conversationId = data.conversationId
      this.messages = []
      this.recallIndex = -1
      await this.refreshConversations()
      this.$nextTick(this.scrollBottom)
    },
    async newConversation () {
      try {
        if (this.busy) {
          await this.stopStream()
        }
        await this.createConversation()
        this.input = ''
        this.pendingImages = []
        this.currentKeyword = ''
        this.recallIndex = -1
        this.startImageTaskPolling()
        this.$message.success('已新建对话')
      } catch (e) {
        this.$message.error(e.message)
      }
    },
    async openConversation (id) {
      if (this.busy) {
        await this.stopStream()
      }
      this.conversationId = id
      this.currentKeyword = ''
      this.historyResults = []
      await this.loadMessages()
      this.startImageTaskPolling()
    },
    async deleteConversation (id) {
      try {
        if (this.conversationId === id && this.busy) {
          await this.stopStream()
        }
        await this.$confirm('删除后 3 天内都不可恢复，确认删除？', '提示', { type: 'warning' })
        await this.del('/agent/conversations/' + encodeURIComponent(id))
        this.conversations = this.conversations.filter((c) => c.id !== id)
        if (this.conversationId === id) {
          this.conversationId = this.conversations.length ? this.conversations[0].id : null
          if (this.conversationId) {
            await this.loadMessages()
            this.startImageTaskPolling()
          } else {
            await this.createConversation()
            this.startImageTaskPolling()
          }
        }
        this.$message.success('已删除')
      } catch (e) {
        if (e !== 'cancel') {
          this.$message.error(e.message || '删除失败')
        }
      }
    },

    // ---------- 发送 ----------
    async send () {
      if (this.busy) {
        return
      }
      const text = (this.input || '').trim()
      if (!text && !this.pendingImages.length) {
        return
      }
      if (!this.conversationId) {
        await this.createConversation()
      }

      const imageIds = this.pendingImages.map((i) => i.imageId)
      const imageUrls = this.pendingImages.map((i) => i.url)
      const userMessage = {
        id: 'local-' + Date.now(),
        role: 'user',
        content: text || '[图片]',
        imageUrls: imageUrls,
        imageIds: imageIds,
        createdAt: Date.now()
      }
      this.messages.push(userMessage)
      this.input = ''
      this.pendingImages = []
      this.busy = true
      this.currentKeyword = ''
      this.recallIndex = -1

      const body = {
        conversationId: this.conversationId,
        message: text,
        modelId: this.modelId,
        imageIds: imageIds
      }

      try {
        if (this.streaming) {
          await this.sendStream(body)
        } else {
          await this.sendNormal(body)
        }
      } catch (e) {
        if (e && e.name === 'AbortError') {
          return
        }
        this.$message.error(e.message || '发送失败')
      } finally {
        this.abortController = null
        this.busy = false
        await this.refreshConversations()
        // 6. 聊天结束后查一次图片任务，只有发现 PENDING/RUNNING 才启动轮询
        this.resumeImageTaskPolling()
        this.$nextTick(this.scrollBottom)
      }
    },
    async sendNormal (body) {
      const data = await this.post('/agent/chat', body)
      const result = data.data || {}
      this.conversationId = result.conversationId || this.conversationId
      this.messages.push({
        id: result.messageId,
        role: 'assistant',
        content: result.content,
        createdAt: result.createdAt,
        inputTokens: result.inputTokens,
        outputTokens: result.outputTokens
      })
      this.$nextTick(this.scrollBottom)
    },
    async sendStream (body, retried) {
      const placeholder = {
        id: 'stream-' + Date.now(),
        role: 'assistant',
        content: '',
        createdAt: Date.now(),
        streaming: true
      }
      this.messages.push(placeholder)
      this.$nextTick(this.scrollBottom)

      let jwtExpired = false
      this.abortController = new AbortController()
      try {
        await streamSse(
          this.$http.adornUrl('/agent/chat/stream'),
          body,
          Vue.cookie.get('token'),
          (event, payload) => {
            if (event === 'meta') {
              this.conversationId = payload.conversationId || this.conversationId
              placeholder.id = payload.messageId || placeholder.id
            } else if (event === 'delta') {
              placeholder.content += payload.content || ''
              this.$nextTick(this.scrollBottom)
            } else if (event === 'done') {
              placeholder.streaming = false
              placeholder.id = payload.messageId || placeholder.id
              placeholder.inputTokens = payload.inputTokens
              placeholder.outputTokens = payload.outputTokens
              this.$nextTick(this.scrollBottom)
            } else if (event === 'error') {
              if (payload.code === 15012 || payload.code === 15001) {
                placeholder.streaming = false
                jwtExpired = true
                return
              }
              placeholder.streaming = false
              placeholder.content = payload.message || 'AI 调用失败'
              this.$message.error(placeholder.content)
            }
          },
          this.abortController ? this.abortController.signal : undefined
        )
      } catch (e) {
        if (e && (e.code === 15012 || e.code === 15001) && !retried) {
          jwtExpired = true
        } else {
          throw e
        }
      }

      if (jwtExpired) {
        const pos = this.messages.indexOf(placeholder)
        if (pos >= 0) {
          this.messages.splice(pos, 1)
        }
        if (retried) {
          this.$message.error('登录已过期，请重新登录')
          const error = new Error('登录已过期，请重新登录')
          error.code = 15012
          throw error
        }
        await this.refreshAgentJwt()
        return this.sendStream(body, true)
      }

      if (placeholder.streaming) {
        // 1. 流结束时还没收到 done/error，说明连接被中断或模型回调异常
        placeholder.streaming = false
        // 2. 给用户明确提示，避免只剩三个打字点一直转
        if (!placeholder.content) {
          placeholder.content = 'AI 回复中断，请重试'
        }
        this.$message.error('AI 回复中断，请重试')
      }
    },
    async stopStream () {
      // 1. 只要还有流式占位消息，就允许停止，即便 busy 已经因为异常被重置
      if (!this.busy && !this.hasStreaming) {
        return
      }
      try {
        await this.post('/agent/chat/stop', { conversationId: this.conversationId })
      } catch (e) {
        // 停止接口失败也让前端断开
      }
      if (this.abortController) {
        this.abortController.abort()
        this.abortController = null
      }
      const last = this.messages[this.messages.length - 1]
      if (last && last.streaming) {
        last.streaming = false
        if (!last.content) {
          last.content = '（已停止）'
        }
      }
      this.busy = false
      this.$message.info('已停止生成')
    },

    // ---------- 图片 ----------
    async uploadImage (event) {
      const file = event.target.files && event.target.files[0]
      event.target.value = ''
      if (file) {
        await this.uploadFile(file)
      }
    },
    async handlePaste (event) {
      const clipboard = event.clipboardData || window.clipboardData
      if (!clipboard || !clipboard.items) {
        return
      }
      for (let i = 0; i < clipboard.items.length; i++) {
        const item = clipboard.items[i]
        if (item.type && item.type.indexOf('image/') === 0) {
          const file = item.getAsFile()
          if (file) {
            event.preventDefault()
            await this.uploadFile(file)
          }
          return
        }
      }
    },
    async uploadFile (file) {
      if (file.size > 8 * 1024 * 1024) {
        this.$message.error('图片不能超过 8MB')
        return
      }
      const form = new FormData()
      form.append('file', file)
      try {
        this.$message.info('正在上传图片...')
        await this.doUpload(form, false)
        this.$message.success('图片已上传')
      } catch (e) {
        this.$message.error(e.message)
      }
    },
    async doUpload (form, retried) {
      const response = await fetch(this.$http.adornUrl('/agent/images'), {
        method: 'POST',
        credentials: 'include',
        body: form
      })
      const data = await response.json()
      if ((data.code === 15012 || data.code === 15001) && !retried) {
        await this.refreshAgentJwt()
        return this.doUpload(form, true)
      }
      if (data.code !== 0) {
        const error = new Error(data.msg || '上传失败')
        error.code = data.code
        throw error
      }
      this.pendingImages.push(data.data)
    },
    preview (url) {
      this.previewUrl = url
      this.previewVisible = true
    },

    // ---------- 搜索 ----------
    async searchHistory () {
      if (!this.historyKeyword) {
        this.historyResults = []
        return
      }
      try {
        const data = await this.get('/agent/conversations/search', {
          keyword: this.historyKeyword,
          limit: 30
        })
        this.historyResults = data.list || []
      } catch (e) {
        this.$message.error(e.message)
      }
    },
    doCurrentSearch () {
      // 当前对话搜索由 computed visibleMessages 完成
    },

    // ---------- 设置 ----------
    openSettings () {
      this.settingsVisible = true
      this.settingsStream = this.streaming
      this.defaultModel = this.modelId
    },
    async saveSettings () {
      try {
        await this.put('/agent/settings', {
          themeColor: this.themeColor,
          streamEnabled: this.settingsStream,
          defaultModel: this.defaultModel,
          customPrompt: this.customPrompt
        })
        this.streaming = this.settingsStream
        this.modelId = this.defaultModel
        this.$message.success('设置已保存')
      } catch (e) {
        this.$message.error(e.message)
      }
    },
    saveSettingsSilent () {
      this.put('/agent/settings', {
        streamEnabled: this.streaming,
        defaultModel: this.modelId
      }).catch(() => {})
    },
    async addCustomModel () {
      try {
        await this.post('/agent/settings/models', this.customForm)
        this.customForm = {
          displayName: '',
          modelName: '',
          baseUrl: '',
          apiKey: '',
          useSharedKey: false,
          supportsVision: false
        }
        await this.loadSettings()
        await this.loadModels()
        this.$message.success('模型已添加')
      } catch (e) {
        this.$message.error(e.message)
      }
    },
    async deleteCustomModel (id) {
      try {
        await this.del('/agent/settings/models/' + id)
        await this.loadSettings()
        await this.loadModels()
        this.$message.success('模型已删除')
      } catch (e) {
        this.$message.error(e.message)
      }
    },

    // ---------- 渲染工具 ----------
    escapeHtml (text) {
      return String(text == null ? '' : text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
    },
    safeUrl (url) {
      if (!url) {
        return ''
      }
      const value = String(url).trim()
      if (/^https?:\/\//i.test(value) || value.indexOf('/oss/') === 0) {
        return value
      }
      return ''
    },
    md (text) {
      let safe = this.escapeHtml(text || '')
      const codeBlocks = []
      safe = safe.replace(/```([\s\S]*?)```/g, (match, code) => {
        codeBlocks.push(code)
        return ' CODE' + (codeBlocks.length - 1) + ' '
      })
      safe = safe.replace(/!\[([^\]]*)\]\(([^)\s]+)\)/g, (match, alt, url) => {
        const link = this.safeUrl(url.replace(/&amp;/g, '&'))
        return link ? '<img src="' + link + '" alt="' + alt + '" style="max-width:100%;border-radius:8px;margin:6px 0;">' : ''
      })
      safe = safe.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (match, label, url) => {
        const link = this.safeUrl(url.replace(/&amp;/g, '&'))
        return link ? '<a href="' + link + '" target="_blank" rel="noopener">' + label + '</a>' : label
      })
      safe = safe.replace(/`([^`]+)`/g, '<code>$1</code>')
      safe = safe.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      safe = safe.replace(/\n/g, '<br>')
      safe = safe.replace(/ CODE(\d+) /g, (match, index) => {
        return '<pre style="background:#12161d;color:#e6edf3;padding:10px;border-radius:8px;overflow:auto;"><code>' + codeBlocks[Number(index)] + '</code></pre>'
      })
      return safe
    },
    formatTime (ts) {
      if (!ts) {
        return ''
      }
      const d = new Date(ts)
      const pad = (n) => (n < 10 ? '0' + n : n)
      return (d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes())
    },
    scrollBottom () {
      this.$nextTick(() => {
        const el = this.$refs.messages
        if (el) {
          el.scrollTop = el.scrollHeight
        }
      })
    },

    // ---------- 图片异步生成 ----------
    startImageTaskPolling () {
      // 1. 先停止旧轮询并重置当前会话的已见任务
      this.stopImageTaskPolling()
      this.imageTaskSeen = {}
      this.imageTaskInited = false
      this.imageTaskNoticeShown = false

      // 2. 只查一次：发现 PENDING/RUNNING 才启动每 4 秒的定时器
      this.pollImageTasks()
    },
    resumeImageTaskPolling () {
      // 1. 已有定时器时交给定时器继续查，避免重复请求
      if (this.imageTaskTimer) {
        return
      }

      // 2. 聊天结束后查一次，发现模型刚提交的图片任务
      if (!this.inited || !this.conversationId) {
        return
      }
      this.pollImageTasks()
    },
    ensureImageTaskPolling () {
      // 1. 已有定时器或没有会话时不重复启动
      if (this.imageTaskTimer || !this.conversationId) {
        return
      }

      // 2. 只有存在 PENDING/RUNNING 任务时才每 4 秒查询
      this.imageTaskTimer = setInterval(this.pollImageTasks, 4000)
    },
    stopImageTaskPolling () {
      // 1. 让正在飞行的请求返回后不再处理
      this.imageTaskPollSeq = (this.imageTaskPollSeq || 0) + 1

      // 2. 清理定时器
      if (this.imageTaskTimer) {
        clearInterval(this.imageTaskTimer)
        this.imageTaskTimer = null
      }
    },
    async pollImageTasks () {
      if (!this.inited || !this.conversationId) {
        return
      }
      const conversationId = this.conversationId
      const seq = (this.imageTaskPollSeq = (this.imageTaskPollSeq || 0) + 1)
      try {
        // 1. 查当前会话的图片任务
        const data = await this.get('/agent/images/tasks', { conversationId })

        // 2. 会话已切换或已停止轮询，丢弃这次响应
        if (seq !== this.imageTaskPollSeq || conversationId !== this.conversationId) {
          return
        }
        const list = data.list || []
        const pending = list.filter((task) => task.status === 'PENDING' || task.status === 'RUNNING')

        // 3. 第一次只记录已有任务，避免把历史图片当成新生成
        if (!this.imageTaskInited) {
          this.imageTaskInited = true
          list.forEach((task) => { this.imageTaskSeen[task.id] = true })
          if (list.some((task) => task.status === 'DONE')) {
            await this.loadMessages()
          }
          if (pending.length) {
            this.ensureImageTaskPolling()
          } else {
            this.stopImageTaskPolling()
          }
          return
        }

        // 4. 有任务在跑时提示一次
        if (pending.length && !this.imageTaskNoticeShown) {
          this.imageTaskNoticeShown = true
          this.$message.info('图片正在后台生成，完成后会自动显示')
        }

        // 5. 失败任务提示原因
        const failed = list.filter((task) => task.status === 'FAILED' && !this.imageTaskSeen[task.id])
        failed.forEach((task) => {
          this.imageTaskSeen[task.id] = true
          this.$message.error('图片生成失败：' + (task.error || '未知错误'))
        })
        if (failed.length && !this.busy) {
          await this.loadMessages()
        }

        // 6. 新完成任务：刷新消息，图片作为一条新 AI 消息出现
        const done = list.filter((task) => task.status === 'DONE' && !this.imageTaskSeen[task.id])
        if (done.length && !this.busy) {
          done.forEach((task) => { this.imageTaskSeen[task.id] = true })
          await this.loadMessages()
          this.imageTaskNoticeShown = false
          this.$message.success('图片已生成')
        }

        // 7. 没有 PENDING/RUNNING 任务就停止轮询，等下一次聊天或页面激活再查
        if (pending.length) {
          this.ensureImageTaskPolling()
        } else {
          this.stopImageTaskPolling()
        }
      } catch (e) {
        // 轮询失败不打扰用户；若仍有定时器，下一轮继续重试
      }
    },

    // ---------- 上方向键回显上一问 ----------
    userHistory () {
      // 1. 只回显当前对话里的用户消息，助手回复跳过
      return this.messages.filter((m) => m.role === 'user')
    },
    resetRecall () {
      // 2. 用户手动改过输入框后，下一次按 ↑ 重新从最新一条开始
      this.recallIndex = -1
    },
    recallPrevious () {
      const history = this.userHistory()
      if (!history.length) {
        return false
      }
      if (this.recallIndex === -1) {
        // 3. 输入框里已有草稿时不动它，避免把用户正在写的内容覆盖掉
        if ((this.input || '').trim() || this.pendingImages.length) {
          return false
        }
        this.recallIndex = history.length - 1
      } else if (this.recallIndex > 0) {
        this.recallIndex -= 1
      } else {
        return true
      }
      this.applyRecall(history[this.recallIndex])
      return true
    },
    recallNext () {
      if (this.recallIndex === -1) {
        return false
      }
      const history = this.userHistory()
      if (this.recallIndex < history.length - 1) {
        this.recallIndex += 1
        this.applyRecall(history[this.recallIndex])
      } else {
        // 4. 已经回到最新一条，再按 ↓ 清空
        this.recallIndex = -1
        this.input = ''
        this.pendingImages = []
      }
      return true
    },
    applyRecall (message) {
      // 5. 文字和图片一起回显；[图片] 只是占位文案，不该填进输入框
      const text = message.content || ''
      this.input = text === '[图片]' ? '' : text
      const imageIds = message.imageIds || []
      const imageUrls = message.imageUrls || []
      this.pendingImages = imageIds.map((imageId, index) => ({
        imageId: imageId,
        url: imageUrls[index] || ''
      }))
      this.$nextTick(() => {
        if (this.$refs.inputBox) {
          this.$refs.inputBox.focus()
        }
      })
    },
    onKeydown (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault()
        this.send()
        return
      }
      if (event.key === 'ArrowUp') {
        if (this.recallPrevious()) {
          event.preventDefault()
        }
        return
      }
      if (event.key === 'ArrowDown') {
        if (this.recallNext()) {
          event.preventDefault()
        }
      }
    }
  }
}
</script>

<style scoped>
.ai-chat-page {
  --ai-primary: #0BB2D4;
  display: flex;
  height: calc(100vh - 120px);
  min-height: 540px;
  background: #f5f7fa;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e8eaed;
}

.ai-side {
  width: 280px;
  flex: 0 0 280px;
  background: #fff;
  border-right: 1px solid #e8eaed;
  display: flex;
  flex-direction: column;
  padding: 14px;
  gap: 10px;
  overflow: hidden;
}

.ai-side-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.ai-side-title {
  font-size: 16px;
  font-weight: 700;
  color: #1f2329;
}

.ai-side-sub {
  font-size: 11px;
  color: #8a9099;
  margin-top: 3px;
}

.ai-session-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-session {
  position: relative;
  padding: 9px 10px;
  border: 1px solid #e8eaed;
  border-radius: 10px;
  cursor: pointer;
  background: #fff;
}

.ai-session:hover,
.ai-session.active {
  border-color: var(--ai-primary, #e4393c);
  background: rgba(228, 57, 60, .05);
}

.ai-session-title {
  font-size: 13px;
  color: #1f2329;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  padding-right: 18px;
}

.ai-session-meta {
  font-size: 10px;
  color: #8a9099;
  margin-top: 3px;
}

.ai-session-del {
  position: absolute;
  right: 8px;
  top: 9px;
  color: #b2b7bf;
  font-size: 13px;
}

.ai-session-del:hover {
  color: #e4393c;
}

.ai-history-results {
  max-height: 220px;
  overflow-y: auto;
  border-top: 1px solid #e8eaed;
  padding-top: 8px;
}

.ai-results-title {
  font-size: 11px;
  color: #8a9099;
  margin-bottom: 6px;
}

.ai-result-item {
  padding: 7px 8px;
  border-radius: 7px;
  background: #f7f8fa;
  margin-bottom: 5px;
  cursor: pointer;
  font-size: 12px;
  color: #1f2329;
  line-height: 1.5;
}

.ai-result-item small {
  display: block;
  color: #8a9099;
  font-size: 10px;
  margin-bottom: 2px;
}

.ai-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}

.ai-main-head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e8eaed;
  /* 1. 所有控件保持一行；宽度不够时优先压缩标题和搜索框，设置按钮不换行 */
  flex-wrap: nowrap;
  overflow: hidden;
}

.ai-main-title {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  color: #1f2329;
}

.ai-model-select {
  flex: 0 1 220px;
  min-width: 140px;
}

.ai-search-input {
  flex: 0 1 180px;
  min-width: 110px;
}

.ai-settings-btn {
  flex: 0 0 auto;
  white-space: nowrap;
}

.ai-main-title-main {
  font-size: 15px;
  font-weight: 700;
}

.ai-main-title span {
  font-size: 11px;
  font-weight: 400;
  line-height: 1.4;
  color: #8a9099;
  margin-left: 0;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 18px 20px;
}

.ai-msg {
  display: flex;
  gap: 9px;
  margin-bottom: 14px;
  align-items: flex-start;
}

.ai-msg.user {
  flex-direction: row-reverse;
}

.ai-avatar {
  flex: 0 0 30px;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  background: #fff;
  border: 1px solid #e8eaed;
  color: #5c636b;
}

.ai-msg.user .ai-avatar {
  background: var(--ai-primary, #e4393c);
  color: #fff;
  border-color: transparent;
}

.ai-bubble {
  max-width: min(720px, calc(100% - 50px));
  background: #fff;
  border: 1px solid #e8eaed;
  border-radius: 12px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #1f2329;
  word-break: break-word;
  box-shadow: 0 1px 2px rgba(20, 24, 31, .04);
}

.ai-msg.user .ai-bubble {
  background: var(--ai-primary, #e4393c);
  color: #fff;
  border-color: transparent;
}

.ai-bubble pre {
  background: #12161d;
  color: #e6edf3;
  padding: 10px;
  border-radius: 8px;
  overflow-x: auto;
}

.ai-bubble code {
  font-family: Consolas, Menlo, monospace;
  font-size: 12px;
}

.ai-images {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}

.ai-images img {
  width: 76px;
  height: 76px;
  object-fit: cover;
  border-radius: 8px;
  cursor: zoom-in;
  border: 1px solid rgba(0, 0, 0, .08);
}

.ai-time {
  font-size: 10px;
  color: #8a9099;
  margin-top: 4px;
}

.ai-msg.user .ai-time {
  color: rgba(255, 255, 255, .75);
  text-align: right;
}

.ai-typing {
  display: inline-flex;
  gap: 3px;
  margin-left: 3px;
}

.ai-typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #8a9099;
  animation: ai-blink 1.1s infinite ease-in-out;
}

.ai-typing i:nth-child(2) { animation-delay: .15s; }
.ai-typing i:nth-child(3) { animation-delay: .3s; }

@keyframes ai-blink {
  0%, 80%, 100% { opacity: .25; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}

.ai-composer {
  flex: 0 0 auto;
  background: #fff;
  border-top: 1px solid #e8eaed;
  padding: 10px 16px 12px;
}

.ai-pending {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}

.ai-chip {
  position: relative;
  width: 52px;
  height: 52px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #e8eaed;
}

.ai-chip img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ai-chip i {
  position: absolute;
  right: 0;
  top: 0;
  width: 16px;
  height: 16px;
  background: rgba(0, 0, 0, .55);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  cursor: pointer;
}

.ai-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.ai-empty {
  color: #8a9099;
  font-size: 12px;
  text-align: center;
  padding: 30px 10px;
  line-height: 1.8;
}

.ai-empty-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2329;
  margin-bottom: 6px;
}

.ai-empty-tip {
  margin-top: 6px;
  color: #b2b7bf;
  font-size: 11px;
}

.ai-custom-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

.ai-custom-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 9px;
  border: 1px solid #e8eaed;
  border-radius: 8px;
  font-size: 12px;
}

.ai-custom-name small {
  display: block;
  color: #8a9099;
  font-size: 10px;
  margin-top: 2px;
}

.ai-divider {
  margin: 16px 0 10px;
  padding-left: 8px;
  border-left: 3px solid var(--ai-primary, #e4393c);
  font-size: 13px;
  font-weight: 700;
  color: #1f2329;
}

@media (max-width: 900px) {
  .ai-side {
    display: none;
  }
}
</style>
