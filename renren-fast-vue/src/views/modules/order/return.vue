<template>
  <div class="mod-config">
    <el-form :inline="true" :model="dataForm" @keyup.enter.native="getDataList()">
      <el-form-item>
        <el-input v-model="dataForm.key" placeholder="订单号 / 商品名 / 会员名" clearable></el-input>
      </el-form-item>
      <el-form-item>
        <el-select v-model="dataForm.status" placeholder="申请状态" clearable>
          <el-option label="0 待处理" value="0"></el-option>
          <el-option label="1 退货中" value="1"></el-option>
          <el-option label="2 已完成" value="2"></el-option>
          <el-option label="3 已拒绝" value="3"></el-option>
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
        prop="orderSn"
        header-align="center"
        align="center"
        width="190"
        label="订单编号">
      </el-table-column>
      <el-table-column
        prop="memberUsername"
        header-align="center"
        align="center"
        label="会员用户名">
      </el-table-column>
      <el-table-column
        header-align="center"
        align="center"
        width="80"
        label="商品图片">
        <template slot-scope="scope">
          <img v-if="scope.row.skuImg" :src="scope.row.skuImg"
               style="width: 56px; height: 56px; object-fit: cover;">
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="skuName"
        header-align="center"
        align="center"
        label="商品名称">
      </el-table-column>
      <el-table-column
        prop="skuCount"
        header-align="center"
        align="center"
        width="80"
        label="退货数量">
      </el-table-column>
      <el-table-column
        prop="returnAmount"
        header-align="center"
        align="center"
        width="100"
        label="退款金额">
      </el-table-column>
      <el-table-column
        prop="returnName"
        header-align="center"
        align="center"
        label="退货人">
      </el-table-column>
      <el-table-column
        prop="returnPhone"
        header-align="center"
        align="center"
        width="120"
        label="退货人电话">
      </el-table-column>
      <el-table-column
        prop="reason"
        header-align="center"
        align="center"
        label="退货原因">
      </el-table-column>
      <el-table-column
        header-align="center"
        align="center"
        width="100"
        label="申请状态">
        <template slot-scope="scope">
          <el-tag :type="statusTagType(scope.row.status)">
            {{ statusText(scope.row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="createTime"
        header-align="center"
        align="center"
        width="160"
        label="申请时间">
      </el-table-column>
      <el-table-column
        prop="handleMan"
        header-align="center"
        align="center"
        label="处理人员">
      </el-table-column>
      <el-table-column
        prop="handleTime"
        header-align="center"
        align="center"
        width="160"
        label="处理时间">
      </el-table-column>
      <el-table-column
        prop="handleNote"
        header-align="center"
        align="center"
        label="处理备注">
      </el-table-column>
      <el-table-column
        fixed="right"
        header-align="center"
        align="center"
        width="100"
        label="操作">
        <template slot-scope="scope">
          <el-button
            type="text"
            size="small"
            :disabled="scope.row.status === 2"
            @click="addOrUpdateHandle(scope.row.id)">
            {{ scope.row.status === 2 ? '已完成' : '处理' }}
          </el-button>
        </template>
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
    <!-- 弹窗：处理退货单 -->
    <add-or-update v-if="addOrUpdateVisible" ref="addOrUpdate" @refreshDataList="getDataList"></add-or-update>
  </div>
</template>

<script>
  import AddOrUpdate from './orderreturnapply-add-or-update'
  export default {
    data () {
      return {
        dataForm: {
          key: '',
          status: ''
        },
        dataList: [],
        pageIndex: 1,
        pageSize: 10,
        totalPage: 0,
        dataListLoading: false,
        addOrUpdateVisible: false
      }
    },
    components: {
      AddOrUpdate
    },
    activated () {
      this.getDataList()
    },
    methods: {
      statusText (status) {
        switch (status) {
          case 0: return '待处理'
          case 1: return '退货中'
          case 2: return '已完成'
          case 3: return '已拒绝'
          default: return '未知'
        }
      },
      statusTagType (status) {
        switch (status) {
          case 0: return 'warning'
          case 1: return 'info'
          case 2: return 'success'
          case 3: return 'danger'
          default: return 'info'
        }
      },
      // 获取数据列表
      getDataList () {
        this.dataListLoading = true
        this.$http({
          url: this.$http.adornUrl('/order/orderreturnapply/list'),
          method: 'get',
          params: this.$http.adornParams({
            'page': this.pageIndex,
            'limit': this.pageSize,
            'key': this.dataForm.key,
            'status': this.dataForm.status
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
      },
      // 处理退货单
      addOrUpdateHandle (id) {
        this.addOrUpdateVisible = true
        this.$nextTick(() => {
          this.$refs.addOrUpdate.init(id)
        })
      }
    }
  }
</script>
