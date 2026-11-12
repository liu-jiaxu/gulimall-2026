<template>
  <el-dialog
    title="处理退货单"
    :close-on-click-modal="false"
    :visible.sync="visible"
    width="720px">
    <el-form :model="dataForm" :rules="dataRule" ref="dataForm" label-width="110px">
      <div class="form-section">申请信息</div>
      <el-row :gutter="10">
        <el-col :span="12">
          <el-form-item label="订单编号">
            <el-input v-model="dataForm.orderSn" disabled></el-input>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="申请时间">
            <el-input v-model="dataForm.createTime" disabled></el-input>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="商品名称">
        <el-input v-model="dataForm.skuName" disabled></el-input>
      </el-form-item>
      <el-form-item label="商品图片">
        <img v-if="dataForm.skuImg" :src="dataForm.skuImg"
             style="width: 90px; height: 90px; object-fit: cover;">
        <span v-else>-</span>
      </el-form-item>
      <el-row :gutter="10">
        <el-col :span="8">
          <el-form-item label="退货数量">
            <el-input v-model="dataForm.skuCount" disabled></el-input>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="退款金额">
            <el-input v-model="dataForm.returnAmount" disabled></el-input>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="会员用户名">
            <el-input v-model="dataForm.memberUsername" disabled></el-input>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="10">
        <el-col :span="12">
          <el-form-item label="退货人">
            <el-input v-model="dataForm.returnName" disabled></el-input>
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="退货人电话">
            <el-input v-model="dataForm.returnPhone" disabled></el-input>
          </el-form-item>
        </el-col>
      </el-row>
      <el-form-item label="退货原因">
        <el-input v-model="dataForm.reason" disabled></el-input>
      </el-form-item>
      <el-form-item label="问题描述">
        <el-input type="textarea" :rows="2" v-model="dataForm.description述" disabled></el-input>
      </el-form-item>

      <div class="form-section">处理信息（可修改）</div>
      <el-form-item label="处理状态" prop="status">
        <el-select v-model="dataForm.status" style="width: 100%;" :disabled="dataForm.originStatus === 2">
          <el-option label="0 待处理" :value="0"></el-option>
          <el-option label="1 退货中" :value="1"></el-option>
          <el-option label="2 已完成" :value="2"></el-option>
          <el-option label="3 已拒绝" :value="3"></el-option>
        </el-select>
        <div v-if="dataForm.status === 2" class="tip-danger">
          状态改为“已完成”后，系统会立即原路退款并回补库存，请确认已经收到退货。
        </div>
      </el-form-item>
      <el-form-item label="处理人员" prop="handleMan">
        <el-input v-model="dataForm.handleMan"></el-input>
      </el-form-item>
      <el-form-item label="处理备注" prop="handleNote">
        <el-input type="textarea" :rows="2" v-model="dataForm.handleNote"></el-input>
      </el-form-item>
      <el-form-item label="收货人" prop="receiveMan">
        <el-input v-model="dataForm.receiveMan"></el-input>
      </el-form-item>
      <el-form-item label="收货电话" prop="receivePhone">
        <el-input v-model="dataForm.receivePhone"></el-input>
      </el-form-item>
      <el-form-item label="收货时间" prop="receiveTime">
        <el-date-picker
          v-model="dataForm.receiveTime"
          type="datetime"
          value-format="yyyy-MM-dd HH:mm:ss"
          placeholder="选择收货时间"
          style="width: 100%;">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="收货备注" prop="receiveNote">
        <el-input type="textarea" :rows="2" v-model="dataForm.receiveNote"></el-input>
      </el-form-item>
      <el-form-item label="公司收货地址" prop="companyAddress">
        <el-input v-model="dataForm.companyAddress"></el-input>
      </el-form-item>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="dataFormSubmit()" :disabled="dataForm.originStatus === 2">确定</el-button>
    </span>
  </el-dialog>
</template>

<script>
  export default {
    data () {
      return {
        visible: false,
        dataForm: {
          id: 0,
          originStatus: '',
          // 只读
          orderSn: '',
          createTime: '',
          skuName: '',
          skuImg: '',
          skuCount: '',
          returnAmount: '',
          memberUsername: '',
          returnName: '',
          returnPhone: '',
          reason: '',
          'description述': '',
          // 可修改
          status: '',
          handleMan: '',
          handleNote: '',
          receiveMan: '',
          receivePhone: '',
          receiveTime: '',
          receiveNote: '',
          companyAddress: ''
        },
        dataRule: {
          status: [
            { required: true, message: '处理状态不能为空', trigger: 'change' }
          ]
        }
      }
    },
    methods: {
      init (id) {
        this.dataForm.id = id || 0
        this.visible = true
        this.$nextTick(() => {
          this.$refs['dataForm'].resetFields()
          if (this.dataForm.id) {
            this.$http({
              url: this.$http.adornUrl(`/order/orderreturnapply/info/${this.dataForm.id}`),
              method: 'get',
              params: this.$http.adornParams()
            }).then(({data}) => {
              if (data && data.code === 0) {
                var apply = data.orderReturnApply || {}
                this.dataForm.originStatus = apply.status
                this.dataForm.orderSn = apply.orderSn
                this.dataForm.createTime = apply.createTime
                this.dataForm.skuName = apply.skuName
                this.dataForm.skuImg = apply.skuImg
                this.dataForm.skuCount = apply.skuCount
                this.dataForm.returnAmount = apply.returnAmount
                this.dataForm.memberUsername = apply.memberUsername
                this.dataForm.returnName = apply.returnName
                this.dataForm.returnPhone = apply.returnPhone
                this.dataForm.reason = apply.reason
                this.dataForm['description述'] = apply['description述']
                this.dataForm.status = apply.status
                this.dataForm.handleMan = apply.handleMan
                this.dataForm.handleNote = apply.handleNote
                this.dataForm.receiveMan = apply.receiveMan
                this.dataForm.receivePhone = apply.receivePhone
                this.dataForm.receiveTime = apply.receiveTime
                this.dataForm.receiveNote = apply.receiveNote
                this.dataForm.companyAddress = apply.companyAddress
              }
            })
          }
        })
      },
      // 只提交后端允许修改的字段；金额、商品、订单信息由后端白名单保护
      dataFormSubmit () {
        this.$refs['dataForm'].validate((valid) => {
          if (valid) {
            this.$http({
              url: this.$http.adornUrl(`/order/orderreturnapply/update`),
              method: 'post',
              data: this.$http.adornData({
                'id': this.dataForm.id,
                'status': this.dataForm.status,
                'handleMan': this.dataForm.handleMan,
                'handleNote': this.dataForm.handleNote,
                'receiveMan': this.dataForm.receiveMan,
                'receivePhone': this.dataForm.receivePhone,
                'receiveTime': this.dataForm.receiveTime,
                'receiveNote': this.dataForm.receiveNote,
                'companyAddress': this.dataForm.companyAddress
              })
            }).then(({data}) => {
              if (data && data.code === 0) {
                this.$message({
                  message: '操作成功',
                  type: 'success',
                  duration: 1500,
                  onClose: () => {
                    this.visible = false
                    this.$emit('refreshDataList')
                  }
                })
              } else {
                this.$message.error(data.msg)
              }
            })
          }
        })
      }
    }
  }
</script>

<style scoped>
  .form-section {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    border-left: 3px solid #409eff;
    padding-left: 8px;
    margin: 6px 0 18px;
  }
  .tip-danger {
    color: #f56c6c;
    font-size: 12px;
    line-height: 18px;
    margin-top: 4px;
  }
</style>
