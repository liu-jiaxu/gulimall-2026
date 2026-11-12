<!-- ============================================================
  multiUpload 组件（多文件图片上传）
  功能说明：
    1. 上传多张图片，支持回显、预览和限制最大数量
    2. 使用后端签发的 MinIO presigned PUT URL 直传（每张图片单独签名）
    3. 组件通过 `v-model` 绑定数组类型的 value（每项为图片的 GET 可访问 URL）
  关键点：回显数组项为 URL 字符串，fileList 通过 value 构造，remove/preview 操作同步修改 value
============================================================== -->
<template>
  <div>
    <!-- el-upload 使用自定义上传：每次上传都会调用 httpRequest 完成一次直传 -->
    <el-upload action="#" list-type="picture-card" :file-list="fileList" :http-request="httpRequest"
      :before-upload="beforeUpload" :on-remove="handleRemove" :on-preview="handlePreview" :limit="maxCount"
      accept=".jpg,.jpeg,.png,.gif,.ico,image/jpeg,image/png,image/gif,image/x-icon,image/vnd.microsoft.icon"
      :on-exceed="handleExceed">
      <i class="el-icon-plus"></i>
    </el-upload>
    <!-- 预览弹窗 -->
    <el-dialog :visible.sync="dialogVisible">
      <img width="100%" :src="dialogImageUrl" alt />
    </el-dialog>
  </div>
</template>
<script>
import axios from 'axios' // 原生 axios，直传 MinIO 不能带全局拦截器注入的 token 头（否则 presigned 签名校验失败）

export default {
  name: 'multiUpload',
  props: {
    // 图片属性数组
    value: Array,
    // 最大上传图片数量
    maxCount: {
      type: Number,
      default: 30
    },
    // 上传到 MinIO 桶时使用的目录名（由调用方指定）。
    // 必须保证有值：后端 @RequestParam("bucketPackageName") 为必填，
    // 若为 undefined，axios 会丢弃该 query 参数，后端将返回 400 Bad Request。
    type: {
      type: String,
      default: 'default'
    }
  },
  data() {
    return {
      dialogVisible: false,
      dialogImageUrl: null
    };
  },
  computed: {
    fileList() {
      const fileList = [];
      const value = this.value || [];
      for (let i = 0; i < value.length; i++) {
        fileList.push({ url: value[i] });
      }
      return fileList;
    }
  },
  methods: {
    /**
     * 将 el-upload 的 fileList 转为 value（URL 数组）并 emit 回父组件
     * @param {Array} fileList - el-upload 的 fileList，每项包含 url 字段
     */
    emitInput(fileList) {
      const value = [];
      for (let i = 0; i < fileList.length; i++) {
        value.push(fileList[i].url);
      }
      console.log('multiUpload.emitInput - emit', value)
      this.$emit('input', value);
    },
    /**
     * 处理移除图片事件
     * @param {Object} file - 被移除的文件对象
     * @param {Array} fileList - 移除后的剩余文件列表
     */
    handleRemove(file, fileList) {
      console.log('multiUpload.handleRemove - removed', file, 'remaining', fileList)
      this.emitInput(fileList);
    },
    /**
     * 预览图片：展示 dialog
     * @param {Object} file - 要预览的文件对象
     */
    handlePreview(file) {
      console.log('multiUpload.handlePreview - preview', file)
      this.dialogVisible = true;
      this.dialogImageUrl = file.url;
    },
    /**
     * 上传前校验文件类型与大小
     * @param {File} file - 待上传的文件对象
     * @returns {Boolean} 是否允许上传
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
        console.warn('multiUpload.beforeUpload - invalid type', file.type, 'ext', ext)
        return false;
      }
      if (file.size > 10 * 1024 * 1024) {
        this.$message.error('文件大小不能超过10MB');
        console.warn('multiUpload.beforeUpload - file too large', file.size)
        return false;
      }
      return true;
    },
    /**
     * 自定义上传：MinIO presigned 直传
     * 1) 向后端要 PUT 直传签名 URL
     * 2) 用原生 axios 把文件二进制 PUT 到该 URL（不带 token 头，否则签名校验失败 403）
     * 3) 成功后将回显 URL push 进 value
     */
    /**
     * 自定义上传逻辑：
     * - 请求后端签发 presigned PUT URL
     * - 使用原生 axios 将文件 PUT 到 minIO
     * - 成功后将后端返回的 fileUrl 追加到 value 并 emit
     */
    async httpRequest(option) {
      const file = option.file;
      try {
        /** 请求后端签发 presigned PUT URL（每张图片单独签名） */
        const bucketPackageName = this.type || 'default';
        console.log('multiUpload.httpRequest - request presigned url', { fileName: file.name, bucketPackageName })
        const res = await this.$http({
          url: this.$http.adornUrl('/thirdparty/admin/system/minio/uploadUrl'),
          method: 'post',
          params: { fileName: file.name, bucketPackageName }
        });
        const data = res.data || {};
        console.log('multiUpload.httpRequest - presigned response', data)
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
        /** 将文件 PUT 到 minIO presigned URL */
        console.log('multiUpload.httpRequest - uploading to minio')
        await axios.put(uploadUrl, file, {
          headers: { 'Content-Type': file.type || 'application/octet-stream' }
        });
        console.log('multiUpload.httpRequest - upload success')
        if (!fileUrl) {
          this.$message.error('后端未返回可访问地址');
          option.onError(new Error('no fileUrl'));
          return;
        }
        /** 将返回的 fileUrl 追加到现有 value 数组并 emit 回父组件 */
        const nextList = [...(this.value || []), fileUrl];
        console.log('multiUpload.httpRequest - emit new value', nextList)
        this.$emit('input', nextList);
        option.onSuccess(fileUrl);
      } catch (e) {
        console.error('multiUpload.httpRequest - error', e);
        this.$message.error('上传失败');
        option.onError(e);
      }
    },
    handleExceed(files, fileList) {
      /**
       * 超出数量限制时的回调
       * @param {File[]} files - 本次被拒绝的文件数组
       * @param {Array} fileList - 当前已有的文件列表
       */
      this.$message({
        message: '最多只能上传' + this.maxCount + '张图片',
        type: 'warning',
        duration: 1000
      });
    }
  }
};
</script>
<style></style>
