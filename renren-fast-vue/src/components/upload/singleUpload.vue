<!-- ============================================================
  singleUpload 组件（单文件图片上传）
  功能说明：
    1. 上传单张图片，支持回显与预览（使用 Element UI 的 el-upload 展示）
    2. 使用后端签发的 MinIO presigned PUT URL 直传（避免通过后端转发大文件）
    3. 直传时使用原生 axios，避免全局拦截器（如注入 token）破坏 MinIO 签名校验
    交互说明：
    - 上传流程：前端请求后端获取 presigned PUT URL -> axios.put 到 presigned URL -> 后端返回可访问的 GET URL
    - 组件通过 `v-model` 双向绑定 value（即最终回显的文件 GET URL）
  注意：回显的 GET URL 可能是带签名的 presigned GET URL，具有有效期，过期后需重新获取。
============================================================== -->
<template>
  <div>
    <!-- el-upload 使用自定义上传：通过 :http-request 指定 httpRequest 方法完成直传 -->
    <el-upload action="#" :show-file-list="showFileList" :file-list="fileList" :multiple="false"
      :http-request="httpRequest" :before-upload="beforeUpload" :on-remove="handleRemove" :on-preview="handlePreview"
      accept=".jpg,.jpeg,.png,.gif,.ico,image/jpeg,image/png,image/gif,image/x-icon,image/vnd.microsoft.icon"
      list-type="picture">
      <el-button size="small" type="primary">点击上传</el-button>
      <div slot="tip" class="el-upload__tip">只能上传jpg/png/ico文件，且不超过10MB</div>
    </el-upload>
    <!-- 预览弹窗：点击预览时展示 fileList[0].url -->
    <el-dialog :visible.sync="dialogVisible">
      <img width="100%" :src="fileList[0] && fileList[0].url" alt="">
    </el-dialog>
  </div>
</template>
<script>
import axios from 'axios' // 原生 axios，直传 MinIO 不能带全局拦截器注入的 token 头（否则 presigned 签名校验失败）

export default {
  name: 'singleUpload',
  props: {
    value: String,
    // 上传到 MinIO 桶时使用的目录名（由调用方指定）。
    // 后端 @RequestParam("bucketPackageName") 为必填，默认值可避免漏传时返回 400 Bad Request。
    type: {
      type: String,
      default: 'default'
    }
  },
  computed: {
    imageUrl() {
      return this.value;
    },
    imageName() {
      if (this.value != null && this.value !== '') {
        return this.value.substr(this.value.lastIndexOf('/') + 1);
      } else {
        return null;
      }
    },
    fileList() {
      if (!this.imageUrl) {
        return [];
      }
      return [{
        name: this.imageName,
        url: this.imageUrl
      }];
    },
    showFileList: {
      get: function () {
        return this.value !== null && this.value !== '' && this.value !== undefined;
      },
      set: function (newValue) {
      }
    }
  },
  data() {
    return {
      dialogVisible: false
    };
  },
  methods: {
    /**
     * 向外部组件 emit 更新后的 file URL
     * @param {String} val - 可访问的图片 URL（通常为带签名的 GET URL）
     */
    emitInput(val) {
      console.log('singleUpload.emitInput - emit', val)
      this.$emit('input', val);
    },
    /**
     * 移除已上传的图片
     * @param {Object} file - 被移除的文件对象
     * @param {Array} fileList - 当前剩余的文件列表
     */
    handleRemove(file, fileList) {
      console.log('singleUpload.handleRemove - removed', file)
      this.emitInput('');
    },
    /**
     * 预览图片（展示弹窗）
     * @param {Object} file - 要预览的文件对象，包含 url 字段
     */
    handlePreview(file) {
      console.log('singleUpload.handlePreview - preview', file)
      this.dialogVisible = true;
    },
    /**
     * 上传前校验：检查文件类型与大小
     * @param {File} file - 待上传文件
     * @returns {Boolean} 是否允许继续上传
     */
    beforeUpload(file) {
      //允许的扩展名与 MIME 类型。.ico 在不同系统/浏览器下 MIME 可能是 image/x-icon、
      //image/vnd.microsoft.icon 甚至 application/octet-stream，因此同时按扩展名兜底判断
      const allowExts = ['jpg', 'jpeg', 'png', 'gif', 'ico'];
      const allowTypes = ['image/jpg', 'image/jpeg', 'image/png', 'image/gif', 'image/x-icon', 'image/vnd.microsoft.icon', 'image/ico'];
      const fileName = file.name || '';
      const ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
      const typeOk = allowTypes.indexOf(file.type) !== -1;
      const extOk = allowExts.indexOf(ext) !== -1;
      if (!typeOk && !extOk) {
        this.$message.error('只支持jpg、png、gif、ico格式的文件！');
        console.warn('singleUpload.beforeUpload - invalid type', file.type, 'ext', ext)
        return false;
      }
      if (file.size > 10 * 1024 * 1024) {
        this.$message.error('文件大小不能超过10MB');
        console.warn('singleUpload.beforeUpload - file too large', file.size)
        return false;
      }
      return true;
    },
    /**
     * 自定义上传：MinIO presigned 直传
     * 1) 向后端要 PUT 直传签名 URL
     * 2) 用原生 axios 把文件二进制 PUT 到该 URL（不带 token 头，否则签名校验失败 403）
     * 3) 回显 URL = uploadUrl 去掉 ?X-Amz-... 签名部分
     */
    async httpRequest(option) {
      const file = option.file;
      try {
        /** 请求后端签发 presigned PUT URL（该接口同时返回用于回显的 GET URL） */
        const bucketPackageName = this.type || 'default';
        console.log('singleUpload.httpRequest - request presigned url', { fileName: file.name, bucketPackageName })
        const res = await this.$http({
          url: this.$http.adornUrl('/thirdparty/admin/system/minio/uploadUrl'),
          method: 'post',
          params: {
            fileName: file.name,         // 后端 @RequestParam("fileName")
            bucketPackageName             // 上传到 MinIO 桶的目录名（不可为 undefined）
          }
        });
        const data = res.data || {};
        /** 后端返回签名信息（uploadUrl, fileUrl 等） */
        console.log('singleUpload.httpRequest - presigned response', data)
        if (data.code !== undefined && data.code !== 0) {
          this.$message.error(data.msg || '获取上传地址失败');
          option.onError(new Error(data.msg));
          return;
        }
        const uploadUrl = data.uploadUrl;   // PUT 直传地址
        const fileUrl = data.fileUrl;       // GET 可访问地址（私有桶下带签名才能显示）
        if (!uploadUrl) {
          this.$message.error('后端未返回上传地址');
          option.onError(new Error('no uploadUrl'));
          return;
        }
        /** 使用原生 axios 将文件二进制 PUT 到 minIO 的 presigned URL */
        console.log('singleUpload.httpRequest - uploading to minio', { uploadUrl })
        await axios.put(uploadUrl, file, {
          headers: { 'Content-Type': file.type || 'application/octet-stream' }
        });
        console.log('singleUpload.httpRequest - upload success')
        /** 回显：后端返回的 fileUrl 通常用于 <img> 展示（可能是带签名的 GET URL） */
        if (!fileUrl) {
          this.$message.error('后端未返回可访问地址');
          option.onError(new Error('no fileUrl'));
          return;
        }
        /** 上传成功后将要 emit 的 fileUrl（用于回显） */
        console.log('singleUpload.httpRequest - emit fileUrl', fileUrl)
        this.emitInput(fileUrl);
        option.onSuccess(fileUrl);
      } catch (e) {
        console.error('singleUpload.httpRequest - error', e);
        this.$message.error('上传失败');
        option.onError(e);
      }
    }
  }
};
</script>
<style></style>
