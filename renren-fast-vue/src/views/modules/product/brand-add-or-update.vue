<!-- ============================================================
  品牌新增 / 修改 弹窗组件
  功能说明：
    1. 用于品牌的新增与修改（复用同一对话框，依据 `brandId` 判断）
    2. 使用 `SingleUpload` 组件上传并回显 logo（上传成功后 dataForm.logo 保存可访问 URL）
    3. 包含前端校验规则（品牌名、logo、介绍、首字母、排序）
  交互说明：
    - 调用方通过 `ref` 调用 `init(id)` 初始化（id 为 0/空 表示新增）
    - 提交成功后触发 `$emit('refreshDataList')` 让父组件刷新列表
============================================================== -->
<template>
  <el-dialog :title="!dataForm.brandId ? '新增' : '修改'" :close-on-click-modal="false" :visible.sync="visible"
    @closed="$emit('closed')">
    <!-- 表单：字段与后端 pms_brand 表字段对应 -->
    <el-form :model="dataForm" :rules="dataRule" ref="dataForm" @keyup.enter.native="dataFormSubmit()"
      label-width="120px">
      <!-- 品牌名：必填 -->
      <el-form-item label="品牌名" prop="name">
        <el-input v-model="dataForm.name" placeholder="品牌名"></el-input>
      </el-form-item>
      <!-- 品牌 logo：使用 SingleUpload 组件上传并回显，value 为 GET 可访问地址（通常为 presigned URL） -->
      <el-form-item label="品牌logo" prop="logo">
        <singleUpload v-model="dataForm.logo" type="brand"></singleUpload>
      </el-form-item>
      <!-- 品牌介绍：必填 -->
      <el-form-item label="介绍" prop="descript">
        <el-input v-model="dataForm.descript" placeholder="介绍"></el-input>
      </el-form-item>
      <!-- 显示状态：Number 类型 1/0，注意 switch 的 active/inactive 值要是数字 -->
      <el-form-item label="显示状态" prop="showStatus">
        <el-switch v-model="dataForm.showStatus" active-color="#13ce66" inactive-color="#ff4949" :active-value="1"
          :inactive-value="0">
        </el-switch>
      </el-form-item>
      <!-- 检索首字母：单个字母校验 -->
      <el-form-item label="检索首字母" prop="firstLetter">
        <el-input v-model="dataForm.firstLetter" placeholder="检索首字母"></el-input>
      </el-form-item>
      <!-- 排序：非负整数 -->
      <el-form-item label="排序" prop="sort">
        <el-input v-model.number="dataForm.sort" placeholder="排序"></el-input>
      </el-form-item>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="dataFormSubmit()">确定</el-button>
    </span>
  </el-dialog>
</template>

<script>
// SingleUpload 组件用于直传 MinIO 并回显可访问的文件 URL（通常为 presigned GET URL）
import SingleUpload from "@/components/upload/singleUpload"

export default {

  // 局部注册 SingleUpload 组件
  components: {
    SingleUpload
  },

  data() {
    return {
      // 控制对话框显示
      visible: false,
      // 表单模型：与后端 pms_brand 表字段对应
      dataForm: {
        brandId: 0,
        name: '',
        logo: '',
        descript: '',
        showStatus: '',
        firstLetter: '',
        sort: ''
      },
      // 表单校验规则：保证提交数据合法
      dataRule: {
        // name: [
        //   { required: true, message: '品牌名不能为空', trigger: 'blur' }
        // ],
        logo: [
          { required: true, message: '品牌logo不能为空', trigger: 'blur' }
        ],
        descript: [
          { required: true, message: '介绍不能为空', trigger: 'blur' }
        ],
        // 首字母：必须为单个字母
        firstLetter: [
          {
            validator: (rule, value, callback) => {
              if (value == '') {
                callback(new Error('检索首字母必须填写'))
              } else if (!/^[a-zA-Z]$/.test(value)) {
                callback(new Error('检索首字母必须是一个字母'))
              } else {
                callback()
              }
            }, trigger: 'blur'
          }
        ],
        // 排序：非负整数
        sort: [
          {
            validator: (rule, value, callback) => {
              if (value != '0' && value == '') {
                callback(new Error('排序必须填写'))
              } else if (!Number.isInteger(value) || value < 0) {
                callback(new Error('排序必须是一个非负整数'))
              } else {
                callback()
              }
            }, trigger: 'blur'
          }
        ]
      }
    }
  },

  methods: {
    /**
     * 初始化弹窗并回显数据（若传入 id，则为编辑）
     * @param {Number|undefined} id - 品牌 id，编辑时传值，新增时不传或传 0
     * - 设置 visible 为 true 展示对话框
     * - 若为编辑，从后端拉取品牌详情并回显到表单
     */
    init(id) {
      this.dataForm.brandId = id || 0
      this.visible = true
      console.log('brand-add-or-update.init - called', { id: this.dataForm.brandId })
      this.$nextTick(() => {
        /** 重置表单校验状态，避免残留的验证错误 */
        this.$refs['dataForm'].resetFields()
        if (this.dataForm.brandId) {
          /** 请求后端获取品牌详情并回显 */
          console.log('brand-add-or-update.init - fetch brand info', { brandId: this.dataForm.brandId })
          this.$http({
            url: this.$http.adornUrl(`/product/brand/info/${this.dataForm.brandId}`),
            method: 'get',
            params: this.$http.adornParams()
          }).then(({ data }) => {
            console.log(data);
            console.log('brand-add-or-update.init - response', data)
            if (data && data.code === 0) {
              // 注意：data.brand 可能是后端接口的返回结构，请以接口实际字段为准
              this.dataForm.name = data.brand.name
              this.dataForm.logo = data.brand.logo
              this.dataForm.descript = data.brand.descript
              this.dataForm.showStatus = data.brand.showStatus
              this.dataForm.firstLetter = data.brand.firstLetter
              this.dataForm.sort = data.brand.sort
            }
          }).catch(err => {
            console.error('brand-add-or-update.init - fetch error', err)
          })
        }
      })
    },
    /**
     * 表单提交处理
     * - 先执行前端校验（validate），通过后调用后端 save 或 update 接口
     * - 在控制台打印提交的 payload 以及后端响应，便于追踪数据库写入结果
     */
    dataFormSubmit() {
      this.$refs['dataForm'].validate((valid) => {
        if (valid) {
          /** 组装提交数据 */
          const payload = {
            'brandId': this.dataForm.brandId || undefined,
            'name': this.dataForm.name,
            'logo': this.dataForm.logo,
            'descript': this.dataForm.descript,
            'showStatus': this.dataForm.showStatus,
            'firstLetter': this.dataForm.firstLetter,
            'sort': this.dataForm.sort
          }
          console.log('brand-add-or-update.dataFormSubmit - payload', payload)
          /** 发送请求：save 或 update */
          this.$http({
            url: this.$http.adornUrl(`/product/brand/${!this.dataForm.brandId ? 'save' : 'update'}`),
            method: 'post',
            data: this.$http.adornData(payload)
          }).then(({ data }) => {
            console.log(data);
            console.log('brand-add-or-update.dataFormSubmit - response', data)
            if (data && data.code === 0) {
              this.$message({
                message: '操作成功',
                type: 'success',
                duration: 1500,
                onClose: () => {
                  // 关闭弹窗并通知父组件刷新列表
                  this.visible = false
                  this.$emit('refreshDataList')
                }
              })
            } else {
              this.$message.error(data.msg)
            }
          }).catch(err => {
            console.error('brand-add-or-update.dataFormSubmit - error', err)
            this.$message.error('请求失败')
          })
        }
      })
    }
  }
}
</script>
