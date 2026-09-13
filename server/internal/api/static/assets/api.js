// VaultForge Console —— API 封装（自动携带 Bearer 会话令牌）
window.API = {
  token: localStorage.getItem('vf_token') || '',
  setToken(t) {
    this.token = t || '';
    if (t) localStorage.setItem('vf_token', t);
    else localStorage.removeItem('vf_token');
  },
  async req(method, path, body, opts) {
    opts = opts || {};
    const headers = Object.assign({}, opts.headers || {});
    if (this.token) headers['Authorization'] = 'Bearer ' + this.token;
    let payload = body;
    const raw = body instanceof Blob || body instanceof ArrayBuffer || typeof body === 'string';
    if (body !== undefined && body !== null && !raw && !(body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await fetch('/api/v1' + path, { method, headers, body: payload });
    if (opts.raw) return res;
    let json = null;
    try { json = await res.json(); } catch (e) { /* 空响应 */ }
    if (!res.ok) {
      const err = new Error((json && json.message) || ('HTTP ' + res.status));
      err.status = res.status;
      err.body = json;
      throw err;
    }
    return json ? json.data : null;
  },
  get(p, o) { return this.req('GET', p, undefined, o); },
  post(p, b, o) { return this.req('POST', p, b, o); },
  put(p, b, o) { return this.req('PUT', p, b, o); },
  patch(p, b, o) { return this.req('PATCH', p, b, o); },
  del(p, o) { return this.req('DELETE', p, undefined, o); },

  // 下载（带令牌）→ 触发浏览器保存
  async download(path, filename) {
    const res = await this.req('GET', path, undefined, { raw: true });
    if (!res.ok) {
      let msg = 'HTTP ' + res.status;
      try { const j = await res.json(); if (j && j.message) msg = j.message; } catch (e) { /* ignore */ }
      throw new Error(msg);
    }
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename || 'download';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 8000);
  },

  // 上传文件（body 即文件内容）
  upload(itemId, dir, file) {
    const q = '?path=' + encodeURIComponent(dir) + '&name=' + encodeURIComponent(file.name);
    return this.req('POST', '/items/' + itemId + '/files/upload' + q, file, {
      headers: { 'Content-Type': 'application/octet-stream' },
    });
  },
};
