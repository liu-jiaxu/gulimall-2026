/**
 * 管理端 AI 流式请求（POST + SSE）。
 *
 * EventSource 只支持 GET，而且不能带 token 请求头，
 * 所以这里用 fetch + ReadableStream 自己解析 SSE。
 */
export default function streamSse (url, body, token, onEvent, signal) {
  return fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'token': token || ''
    },
    body: JSON.stringify(body),
    signal: signal
  }).then(async (response) => {
    const contentType = response.headers.get('content-type') || ''
    if (contentType.indexOf('text/event-stream') < 0) {
      // 未登录 / token 过期 / 参数错误时后端返回 JSON
      const text = await response.text()
      let json = {}
      try {
        json = text ? JSON.parse(text) : {}
      } catch (e) {
        json = {}
      }
      const error = new Error(json.msg || 'AI 请求失败')
      error.code = json.code
      throw error
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const step = await reader.read()
      if (step.done) {
        break
      }
      buffer += decoder.decode(step.value, { stream: true })
      buffer = buffer.replace(/\r/g, '')
      let index
      while ((index = buffer.indexOf('\n\n')) >= 0) {
        const chunk = buffer.slice(0, index)
        buffer = buffer.slice(index + 2)
        handleChunk(chunk, onEvent)
      }
    }
  })
}

function handleChunk (chunk, onEvent) {
  let eventName = 'message'
  let data = ''
  chunk.split('\n').forEach((line) => {
    if (line.indexOf('event:') === 0) {
      eventName = line.slice(6).trim()
    }
    if (line.indexOf('data:') === 0) {
      data += line.slice(5).trim()
    }
  })
  if (!data) {
    return
  }
  let payload = {}
  try {
    payload = JSON.parse(data)
  } catch (e) {
    return
  }
  onEvent(eventName, payload)
}
