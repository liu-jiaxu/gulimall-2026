<template>
  <div class="mod-config">
    <el-form :inline="true" :model="dataForm" @keyup.enter.native="getDataList()">
      <el-form-item>
        <el-input v-model="dataForm.key" placeholder="退款流水号 / 关联 id" clearable></el-input>
      </el-form-item>
      <el-form-item>
        <el-select v-model="dataForm.refundType" placeholder="退款类型" clearable>
          <el-option label="1 超时支付自动退款" value="1"></el-option>
          <el-option label="2 退货退款" value="2"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="dataForm.refundStatus" placeholder="退款状态" clearable>
          <el-option label="1 成功" value="1"></el-option>
          <el-option label="2 失败/待重试" value="2"></el-option>
          <el-option label="3 永久失败/需人工处理" value="3"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button @click="getDataList()">查询</el-button>
      </el-form-item>
    </el-form>
    <el-table
      :data="dataList"
      border
      v-loading="dataListLoading"
      style="width: 100%;">
      <el-table-column
        prop="id"
        header-align="center"
        align="center"
        width="60"
        label="id">
      </el-table-column>
      <el-table-column
        prop="refundSn"
        header-align="center"
        align="center"
        width="240"
        label="退款流水号">
      </el-table-column>
      <el-table-column
        header-align="center"
        align="center"
        width="150"
        label="退款类型">
        <template slot-scope="scope">
          {{ refundTypeText(scope.row.refundType) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="orderReturnId"
        header-align="center"
        align="center"
        width="110"
        label="关联 id">
      </el-table-column>
      <el-table-column
        prop="refund"
        header-align="center"
        align="center"
        width="110"
        label="退款金额">
      </el-table-column>
      <el-table-column
        header-align="center"
        align="center"
        width="130"
        label="退款状态">
        <template slot-scope="scope">
          <el-tag :type="scope.row.refundStatus === 1 ? 'success' : 'danger'">
            {{ scope.row.refundStatus === 1 ? '成功'
              : (scope.row.refundStatus === 3 ? '永久失败/需人工处理' : '失败/待重试') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        header-align="center"
        align="center"
        width="100"
        label="退款渠道">
        <template slot-scope="scope">
          {{ refundChannelText(scope.row.refundChannel) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="refundContent"
        header-align="center"
        align="center"
        label="退款内容">
      </el-table-column>
    </el-table>
    <el-pagination
      @size-change="sizeChangeHandle"
      @current-change="currentChangeHandle"
      :current-page="pageIndex"
      :page-sizes="[10, 20, 50, 100]"
      :page-size="pageSize"
      :total="totalPage"
      layout="total, sizes, prev, pager, next, jumper">
    </el-pagination>
  </div>
</template>

<script>
  export default {
    data () {
      return {
        dataForm: {
          key: '',
          refundType: '',
          refundStatus: ''
        },
        dataList: [],
        pageIndex: 1,
        pageSize: 10,
        totalPage: 0,
        dataListLoading: false
      }
    },
    activated () {
      this.getDataList()
    },
    methods: {
      refundTypeText (type) {
        if (type === 2) {
          return '退货退款'
        }
        if (type === 1 || type === null || type === undefined) {
          return '超时支付自动退款'
        }
        return '未知'
      },
      refundChannelText (channel) {
        switch (channel) {
          case 1: return '支付宝'
          case 2: return '微信'
          case 3: return '银联'
          case 4: return '汇款'
          default: return '-'
        }
      },
      // 获取数据列表
      getDataList () {
        this.dataListLoading = true
        this.$http({
          url: this.$http.adornUrl('/order/refundinfo/list'),
          method: 'get',
          params: this.$http.adornParams({
            'page': this.pageIndex,
            'limit': this.pageSize,
            'key': this.dataForm.key,
            'refundType': this.dataForm.refundType,
            'refundStatus': this.dataForm.refundStatus
          })
        }).then(({data}) => {
          if (data && data.code === 0) {
            this.dataList = data.page.list
            this.totalPage = data.page.totalCount
          } else {
            this.dataList = []
            this.totalPage = 0
          }
          this.dataListLoading = false
        })
      },
      // 每页数
      sizeChangeHandle (val) {
        this.pageSize = val
        this.pageIndex = 1
        this.getDataList()
      },
      // 当前页
      currentChangeHandle (val) {
        this.pageIndex = val
        this.getDataList()
      }
    }
  }
</script>
