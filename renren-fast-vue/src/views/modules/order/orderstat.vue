<template>
  <el-dialog
    title="交易统计"
    :visible.sync="visible"
    width="1180px"
    :close-on-click-modal="false"
    @closed="disposeCharts">
    <el-form :inline="true" :model="query">
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="query.range"
          type="datetimerange"
          value-format="yyyy-MM-dd HH:mm:ss"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          :default-time="['00:00:00', '23:59:59']"
          style="width: 380px;">
        </el-date-picker>
      </el-form-item>
      <el-form-item label="商品名称">
        <el-input v-model="query.keyword" placeholder="输入商品名称筛选" clearable style="width: 220px;"></el-input>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="loadData">查询</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="16" class="stat-cards">
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">交易额</div>
          <div class="stat-value">￥{{ money(summary.totalAmount) }}</div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">订单数</div>
          <div class="stat-value">{{ summary.orderCount || 0 }}</div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="stat-card">
          <div class="stat-label">商品件数</div>
          <div class="stat-value">{{ summary.itemCount || 0 }}</div>
        </div>
      </el-col>
    </el-row>

    <div id="order-stat-trend" class="chart chart-lg"></div>

    <el-row :gutter="16">
      <el-col :span="12">
        <div class="chart-title">商品销量 Top10</div>
        <div id="order-stat-products" class="chart"></div>
      </el-col>
      <el-col :span="6">
        <div class="chart-title">支付方式</div>
        <div id="order-stat-pay" class="chart"></div>
      </el-col>
      <el-col :span="6">
        <div class="chart-title">订单状态</div>
        <div id="order-stat-status" class="chart"></div>
      </el-col>
    </el-row>
  </el-dialog>
</template>

<script>
  import echarts from 'echarts'

  export default {
    data () {
      return {
        visible: false,
        loading: false,
        query: {
          range: [],
          keyword: ''
        },
        summary: {},
        charts: {}
      }
    },
    methods: {
      init () {
        this.visible = true
        if (!this.query.range || this.query.range.length !== 2) {
          var end = new Date()
          var start = new Date(end.getTime() - 29 * 24 * 60 * 60 * 1000)
          this.query.range = [this.formatTime(start), this.formatTime(end)]
        }
        this.$nextTick(() => this.loadData())
      },
      formatTime (date) {
        var pad = function (n) { return n < 10 ? '0' + n : '' + n }
        return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' '
          + pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds())
      },
      money (value) {
        var num = Number(value || 0)
        return num.toFixed(2)
      },
      loadData () {
        this.loading = true
        var range = this.query.range || []
        this.$http({
          url: this.$http.adornUrl('/order/order/stat'),
          method: 'get',
          params: this.$http.adornParams({
            'startTime': range[0] || '',
            'endTime': range[1] || '',
            'keyword': this.query.keyword || ''
          })
        }).then(({data}) => {
          this.loading = false
          if (data && data.code === 0) {
            var stat = data.stat || {}
            this.summary = stat
            // 弹窗展开动画结束后再初始化图表，否则容器宽度可能是 0
            this.$nextTick(() => setTimeout(() => this.renderCharts(stat), 50))
          } else {
            this.$message.error((data && data.msg) || '统计查询失败')
          }
        }).catch(() => {
          this.loading = false
          this.$message.error('统计查询失败')
        })
      },
      getChart (id) {
        if (!this.charts[id]) {
          this.charts[id] = echarts.init(document.getElementById(id))
        }
        return this.charts[id]
      },
      renderCharts (stat) {
        this.renderTrend(stat.dailyTrend || [])
        this.renderProducts(stat.topProducts || [])
        this.renderPie('order-stat-pay', '支付方式', stat.payTypeDistribution || [])
        this.renderPie('order-stat-status', '订单状态', stat.statusDistribution || [])
      },
      renderTrend (rows) {
        var dates = rows.map(function (r) { return r.date })
        var amounts = rows.map(function (r) { return Number(r.amount || 0) })
        var counts = rows.map(function (r) { return Number(r.orderCount || 0) })
        this.getChart('order-stat-trend').setOption({
          title: { text: '每日交易趋势', left: 10, top: 8, textStyle: { fontSize: 14 } },
          tooltip: { trigger: 'axis' },
          legend: { data: ['交易额', '订单数'], right: 20, top: 8 },
          grid: { left: 60, right: 60, top: 60, bottom: 40 },
          xAxis: { type: 'category', data: dates, boundaryGap: true },
          yAxis: [
            { type: 'value', name: '交易额(元)', axisLabel: { formatter: '{value}' } },
            { type: 'value', name: '订单数', minInterval: 1 }
          ],
          series: [
            {
              name: '交易额',
              type: 'line',
              smooth: true,
              areaStyle: { opacity: 0.12 },
              itemStyle: { color: '#409EFF' },
              data: amounts
            },
            {
              name: '订单数',
              type: 'bar',
              yAxisIndex: 1,
              barMaxWidth: 24,
              itemStyle: { color: '#F2C97D' },
              data: counts
            }
          ]
        }, true)
      },
      renderProducts (rows) {
        var names = rows.map(function (r) {
          var name = r.skuName || ('SKU ' + r.skuId)
          return name.length > 18 ? name.substring(0, 18) + '…' : name
        }).reverse()
        var quantities = rows.map(function (r) { return Number(r.quantity || 0) }).reverse()
        var amounts = rows.map(function (r) { return Number(r.amount || 0) }).reverse()
        this.getChart('order-stat-products').setOption({
          tooltip: {
            trigger: 'axis',
            axisPointer: { type: 'shadow' },
            formatter: function (params) {
              var index = params[0].dataIndex
              return params[0].name + '<br/>销量：' + quantities[index]
                + '<br/>销售额：￥' + amounts[index].toFixed(2)
            }
          },
          grid: { left: 150, right: 40, top: 20, bottom: 30 },
          xAxis: { type: 'value', minInterval: 1 },
          yAxis: { type: 'category', data: names },
          series: [{
            type: 'bar',
            barMaxWidth: 20,
            itemStyle: { color: '#67C23A' },
            data: quantities
          }]
        }, true)
      },
      renderPie (id, title, rows) {
        var data = rows.map(function (r) {
          return { name: r.name, value: Number(r.value || 0) }
        })
        this.getChart(id).setOption({
          title: {
            text: data.length ? '' : '暂无数据',
            left: 'center',
            top: 'middle',
            textStyle: { color: '#999', fontSize: 13, fontWeight: 'normal' }
          },
          tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
          legend: { bottom: 0, type: 'scroll' },
          series: [{
            name: title,
            type: 'pie',
            radius: ['38%', '62%'],
            center: ['50%', '45%'],
            data: data
          }]
        }, true)
      },
      disposeCharts () {
        Object.keys(this.charts).forEach((id) => {
          if (this.charts[id]) {
            this.charts[id].dispose()
          }
        })
        this.charts = {}
      }
    }
  }
</script>

<style scoped>
  .stat-cards {
    margin-bottom: 16px;
  }
  .stat-card {
    background: #f5f7fa;
    border-radius: 6px;
    padding: 16px 20px;
  }
  .stat-label {
    color: #909399;
    font-size: 13px;
    margin-bottom: 8px;
  }
  .stat-value {
    color: #303133;
    font-size: 24px;
    font-weight: 600;
  }
  .chart {
    width: 100%;
    height: 320px;
  }
  .chart-lg {
    height: 300px;
    margin-bottom: 8px;
  }
  .chart-title {
    font-size: 14px;
    font-weight: 600;
    color: #303133;
    padding: 6px 0 0 4px;
  }
</style>
