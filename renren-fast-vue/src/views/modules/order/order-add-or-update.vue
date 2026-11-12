<template>
  <el-dialog
    :title="!dataForm.id ? '新增' : '修改订单'"
    :close-on-click-modal="false"
    :visible.sync="visible">
    <el-form :model="dataForm" :rules="dataRule" ref="dataForm" @keyup.enter.native="dataFormSubmit()" label-width="110px">
      <el-form-item label="订单号">
        <el-input v-model="dataForm.orderSn" disabled></el-input>
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="dataForm.memberUsername" disabled></el-input>
      </el-form-item>
      <el-form-item label="应付总额">
        <el-input v-model="dataForm.payAmount" disabled></el-input>
      </el-form-item>
      <el-form-item label="订单状态" prop="status">
        <el-select v-model="dataForm.status" placeholder="请选择订单状态" style="width: 100%;">
          <el-option label="0 待付款" :value="0"></el-option>
          <el-option label="1 待发货" :value="1"></el-option>
          <el-option label="2 已发货" :value="2"></el-option>
          <el-option label="3 已完成" :value="3"></el-option>
          <el-option label="4 已关闭" :value="4"></el-option>
          <el-option label="5 无效订单" :value="5"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="物流公司" prop="deliveryCompany">
        <el-input v-model="dataForm.deliveryCompany" placeholder="如：顺丰速运"></el-input>
      </el-form-item>
      <el-form-item label="物流单号" prop="deliverySn">
        <el-input v-model="dataForm.deliverySn" placeholder="发货后填写"></el-input>
      </el-form-item>
      <el-form-item label="收货人姓名" prop="receiverName">
        <el-input v-model="dataForm.receiverName"></el-input>
      </el-form-item>
      <el-form-item label="收货人电话" prop="receiverPhone">
        <el-input v-model="dataForm.receiverPhone"></el-input>
      </el-form-item>
      <el-form-item label="省份/直辖市" prop="receiverProvince">
        <el-input v-model="dataForm.receiverProvince"></el-input>
      </el-form-item>
      <el-form-item label="城市" prop="receiverCity">
        <el-input v-model="dataForm.receiverCity"></el-input>
      </el-form-item>
      <el-form-item label="区" prop="receiverRegion">
        <el-input v-model="dataForm.receiverRegion"></el-input>
      </el-form-item>
      <el-form-item label="详细地址" prop="receiverDetailAddress">
        <el-input v-model="dataForm.receiverDetailAddress"></el-input>
      </el-form-item>
      <el-form-item label="订单备注" prop="note">
        <el-input type="textarea" :rows="3" v-model="dataForm.note"></el-input>
      </el-form-item>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="dataFormSubmit()">确定</el-button>
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
          orderSn: '',
          memberUsername: '',
          payAmount: '',
          status: '',
          deliveryCompany: '',
          deliverySn: '',
          receiverName: '',
          receiverPhone: '',
          receiverProvince: '',
          receiverCity: '',
          receiverRegion: '',
          receiverDetailAddress: '',
          note: ''
        },
        dataRule: {
          status: [
            { required: true, message: '订单状态不能为空', trigger: 'change' }
          ],
          receiverName: [
            { required: true, message: '收货人姓名不能为空', trigger: 'blur' }
          ],
          receiverPhone: [
            { required: true, message: '收货人电话不能为空', trigger: 'blur' }
          ],
          receiverDetailAddress: [
            { required: true, message: '详细地址不能为空', trigger: 'blur' }
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
              url: this.$http.adornUrl(`/order/order/info/${this.dataForm.id}`),
              method: 'get',
              params: this.$http.adornParams()
            }).then(({data}) => {
              if (data && data.code === 0) {
                var order = data.order || {}
                this.dataForm.orderSn = order.orderSn
                this.dataForm.memberUsername = order.memberUsername
                this.dataForm.payAmount = order.payAmount
                this.dataForm.status = order.status
                this.dataForm.deliveryCompany = order.deliveryCompany
                this.dataForm.deliverySn = order.deliverySn
                this.dataForm.receiverName = order.receiverName
                this.dataForm.receiverPhone = order.receiverPhone
                this.dataForm.receiverProvince = order.receiverProvince
                this.dataForm.receiverCity = order.receiverCity
                this.dataForm.receiverRegion = order.receiverRegion
                this.dataForm.receiverDetailAddress = order.receiverDetailAddress
                this.dataForm.note = order.note
              }
            })
          }
        })
      },
      // 表单提交：只提交后台允许修改的关键字段
      dataFormSubmit () {
        this.$refs['dataForm'].validate((valid) => {
          if (valid) {
            this.$http({
              url: this.$http.adornUrl(`/order/order/${!this.dataForm.id ? 'save' : 'update'}`),
              method: 'post',
              data: this.$http.adornData({
                'id': this.dataForm.id || undefined,
                'status': this.dataForm.status,
                'deliveryCompany': this.dataForm.deliveryCompany,
                'deliverySn': this.dataForm.deliverySn,
                'receiverName': this.dataForm.receiverName,
                'receiverPhone': this.dataForm.receiverPhone,
                'receiverProvince': this.dataForm.receiverProvince,
                'receiverCity': this.dataForm.receiverCity,
                'receiverRegion': this.dataForm.receiverRegion,
                'receiverDetailAddress': this.dataForm.receiverDetailAddress,
                'note': this.dataForm.note
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
