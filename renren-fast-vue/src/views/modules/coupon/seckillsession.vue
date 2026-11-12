<template>
  <div class="mod-config">
    <el-form :inline="true" :model="dataForm" @keyup.enter.native="getDataList()">
      <el-form-item>
        <el-input v-model="dataForm.key" placeholder="参数名" clearable></el-input>
      </el-form-item>
      <el-form-item>
        <el-button @click="getDataList()">查询</el-button>
        <el-button
          v-if="isAuth('coupon:seckillsession:save')"
          type="primary"
          @click="addOrUpdateHandle()"
        >新增</el-button>
        <el-button
          v-if="isAuth('coupon:seckillsession:delete')"
          type="danger"
          @click="deleteHandle()"
          :disabled="dataListSelections.length <= 0"
        >批量删除</el-button>
        <el-button
          v-if="isAuth('coupon:seckillskurelation:list')"
          type="primary"
          @click="viewAllRelations()"
        >查看关联商品</el-button>
      </el-form-item>
    </el-form>
    <el-table
      :data="dataList"
      border
      v-loading="dataListLoading"
      @selection-change="selectionChangeHandle"
      style="width: 100%;"
    >
      <el-table-column type="selection" header-align="center" align="center" width="50"></el-table-column>
      <el-table-column prop="id" header-align="center" align="center" label="id"></el-table-column>
      <el-table-column prop="name" header-align="center" align="center" label="场次名称"></el-table-column>
      <el-table-column prop="startTime" header-align="center" align="center" label="开始时间"></el-table-column>
      <el-table-column prop="endTime" header-align="center" align="center" label="结束时间"></el-table-column>
      <el-table-column header-align="center" align="center" label="秒杀状态">
        <template slot-scope="scope">
          <el-tag :type="stateTagType(scope.row)">{{ stateLabel(scope.row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column header-align="center" align="center" label="启用状态">
        <template slot-scope="scope">
          <el-tag :type="Number(scope.row.status) === 1 ? 'success' : 'info'">
            {{ Number(scope.row.status) === 1 ? "启用" : "未启用" }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" header-align="center" align="center" label="创建时间"></el-table-column>
      <el-table-column fixed="right" header-align="center" align="center" width="320" label="操作">
        <template slot-scope="scope">
          <!-- 未开始：可以关联、修改、下架、物理删除；关联商品里即可查看 -->
          <template v-if="stateOf(scope.row) === 0">
            <el-button type="text" size="small" @click="relationProduct(scope.row)">关联商品</el-button>
            <el-button type="text" size="small" @click="addOrUpdateHandle(scope.row.id)">修改</el-button>
            <el-button
              v-if="scope.row.status !== 0"
              type="text"
              size="small"
              @click="offlineHandle(scope.row.id)"
            >下架</el-button>
            <el-button type="text" size="small" @click="deleteHandle(scope.row.id)">删除</el-button>
          </template>
          <!-- 进行中：只能查看商品和取消 -->
          <template v-else-if="stateOf(scope.row) === 1">
            <el-button type="text" size="small" @click="viewRelation(scope.row)">查看商品</el-button>
            <el-button type="text" size="small" @click="cancelHandle(scope.row.id)">取消</el-button>
          </template>
          <!-- 停止中：等 30 分钟宽限期 -->
          <template v-else-if="stateOf(scope.row) === 2">
            <span style="color: #909399; font-size: 12px;">结算中</span>
          </template>
          <!-- 已结束/已取消：可以管理关联（只能删）、下架、物理删除 -->
          <template v-else>
            <el-button type="text" size="small" @click="relationProduct(scope.row)">关联商品</el-button>
            <el-button
              v-if="scope.row.status !== 0"
              type="text"
              size="small"
              @click="offlineHandle(scope.row.id)"
            >下架</el-button>
            <el-button type="text" size="small" @click="deleteHandle(scope.row.id)">删除</el-button>
          </template>
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
      layout="total, sizes, prev, pager, next, jumper"
    ></el-pagination>
    <!-- 弹窗, 新增 / 修改 -->
    <add-or-update v-if="addOrUpdateVisible" ref="addOrUpdate" @refreshDataList="getDataList"></add-or-update>
    <div>
      <el-dialog
        append-to-body
        :close-on-click-modal="false"
        :visible.sync="visible"
        title="关联秒杀商品"
        width="60%"
      >
        <seckillsku-relation
          ref="seckillskuRelation"
          :sessionId="currentId"
          :sessionState="currentState"
          :sessionStatus="currentStatus"
        ></seckillsku-relation>
      </el-dialog>
    </div>
    <!-- 只读查看关联商品 -->
    <el-dialog
      append-to-body
      :close-on-click-modal="false"
      :visible.sync="viewVisible"
      :title="'关联商品- ' + viewSessionName"
      width="70%"
    >
      <el-form :inline="true">
        <el-form-item>
          <el-input
            v-model="viewKeyword"
            placeholder="按 SKU ID 或商品名称模糊查询"
            clearable
            @keyup.enter.native="loadViewList"
            style="width: 320px;"
          ></el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadViewList">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="viewList" border v-loading="viewLoading" style="width: 100%;">
        <el-table-column prop="skuId" label="SKU ID" width="100" align="center"></el-table-column>
        <el-table-column prop="skuName" label="商品名称"></el-table-column>
        <el-table-column prop="seckillPrice" label="秒杀价" width="110" align="center"></el-table-column>
        <el-table-column prop="seckillCount" label="总量" width="90" align="center"></el-table-column>
        <el-table-column prop="seckillLimit" label="限购" width="90" align="center"></el-table-column>
        <el-table-column prop="seckillSort" label="排序" width="80" align="center"></el-table-column>
        <el-table-column prop="wareId" label="仓库ID" width="90" align="center"></el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="viewVisible = false">关闭</el-button>
      </span>
    </el-dialog>
    <!-- 全局只读查看全部关联商品 -->
    <el-dialog
      append-to-body
      :close-on-click-modal="false"
      :visible.sync="allViewVisible"
      title="全部关联商品"
      width="80%"
    >
      <el-form :inline="true">
        <el-form-item>
          <el-input
            v-model="allKeyword"
            placeholder="按 SKU ID、商品名称或场次名称模糊查询"
            clearable
            @keyup.enter.native="loadAllRelations"
            style="width: 360px;"
          ></el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadAllRelations">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="allList" border v-loading="allLoading" height="520" style="width: 100%;">
        <el-table-column prop="sessionId" label="场次ID" width="80" align="center"></el-table-column>
        <el-table-column prop="sessionName" label="场次名称" width="150"></el-table-column>
        <el-table-column prop="skuId" label="SKU ID" width="90" align="center"></el-table-column>
        <el-table-column prop="skuName" label="商品名称"></el-table-column>
        <el-table-column prop="seckillPrice" label="秒杀价" width="100" align="center"></el-table-column>
        <el-table-column prop="seckillCount" label="总量" width="80" align="center"></el-table-column>
        <el-table-column prop="seckillLimit" label="限购" width="80" align="center"></el-table-column>
        <el-table-column prop="seckillSort" label="排序" width="70" align="center"></el-table-column>
        <el-table-column prop="wareId" label="仓库" width="70" align="center"></el-table-column>
      </el-table>
      <span slot="footer">
        <el-button @click="allViewVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import AddOrUpdate from "./seckillsession-add-or-update";
import SeckillskuRelation from "./seckillskurelation";
export default {
  data() {
    return {
      dataForm: {
        key: ""
      },
      dataList: [],
      pageIndex: 1,
      pageSize: 10,
      totalPage: 0,
      dataListLoading: false,
      dataListSelections: [],
      addOrUpdateVisible: false,
      visible: false,
      currentId: 0,
      currentState: 0,
      currentStatus: 1,
      viewVisible: false,
      viewSessionId: null,
      viewSessionName: "",
      viewKeyword: "",
      viewList: [],
      viewLoading: false,
      allViewVisible: false,
      allKeyword: "",
      allList: [],
      allLoading: false
    };
  },
  components: {
    AddOrUpdate,
    SeckillskuRelation
  },
  activated() {
    this.getDataList();
  },
  methods: {
    // 获取数据列表
    getDataList() {
      this.dataListLoading = true;
      this.$http({
        url: this.$http.adornUrl("/coupon/seckillsession/list"),
        method: "get",
        params: this.$http.adornParams({
          page: this.pageIndex,
          limit: this.pageSize,
          key: this.dataForm.key
        })
      }).then(({ data }) => {
        if (data && data.code === 0) {
          this.dataList = data.page.list;
          this.totalPage = data.page.totalCount;
        } else {
          this.dataList = [];
          this.totalPage = 0;
        }
        this.dataListLoading = false;
      });
    },
    getRealtionDataList() {},
    // 每页数
    sizeChangeHandle(val) {
      this.pageSize = val;
      this.pageIndex = 1;
      this.getDataList();
    },
    // 当前页
    currentChangeHandle(val) {
      this.pageIndex = val;
      this.getDataList();
    },
    // 多选
    selectionChangeHandle(val) {
      this.dataListSelections = val;
    },
    // 新增 / 修改
    addOrUpdateHandle(id) {
      this.addOrUpdateVisible = true;
      this.$nextTick(() => {
        this.$refs.addOrUpdate.init(id);
      });
    },
    relationProduct(row) {
      this.visible = true;
      this.currentId = row.id;
      this.currentState = this.stateOf(row);
      this.currentStatus = row.status == null ? 1 : Number(row.status);
      this.$nextTick(() => {
        this.$refs.seckillskuRelation.getDataList();
      });
    },
    // 只读查看关联商品
    viewRelation(row) {
      this.viewVisible = true;
      this.viewSessionId = row.id;
      this.viewSessionName = row.name || "";
      this.viewKeyword = "";
      this.viewList = [];
      this.loadViewList();
    },
    loadViewList() {
      if (!this.viewSessionId) {
        return;
      }
      this.viewLoading = true;
      this.$http({
        url: this.$http.adornUrl(
          "/coupon/seckillskurelation/session/" + this.viewSessionId
        ),
        method: "get",
        params: this.$http.adornParams({
          keyword: this.viewKeyword
        })
      }).then(({ data }) => {
        this.viewList = data && data.code === 0 ? data.list || [] : [];
        this.viewLoading = false;
      }).catch(() => {
        this.viewLoading = false;
      });
    },
    // 顶部入口：只读查看全部关联商品
    viewAllRelations() {
      this.allViewVisible = true;
      this.allKeyword = "";
      this.allList = [];
      this.loadAllRelations();
    },
    loadAllRelations() {
      this.allLoading = true;
      this.$http({
        url: this.$http.adornUrl("/coupon/seckillskurelation/all"),
        method: "get",
        params: this.$http.adornParams({
          keyword: this.allKeyword
        })
      }).then(({ data }) => {
        this.allList = data && data.code === 0 ? data.list || [] : [];
        this.allLoading = false;
      }).catch(() => {
        this.allLoading = false;
      });
    },
    // 秒杀有效状态：存的状态 + 开始/结束时间
    stateOf(row) {
      const stored = row.seckillState == null ? 0 : Number(row.seckillState);
      if (stored === 2 || stored === 3 || stored === 4) {
        return stored;
      }
      const now = Date.now();
      const start = row.startTime ? new Date(row.startTime).getTime() : null;
      const end = row.endTime ? new Date(row.endTime).getTime() : null;
      if (start && now < start) {
        return 0;
      }
      if (end && now >= end) {
        return 3;
      }
      return 1;
    },
    stateLabel(row) {
      return ["未开始", "进行中", "停止中", "已结束", "已取消"][this.stateOf(row)] || "未知";
    },
    stateTagType(row) {
      return ["", "success", "warning", "info", "danger"][this.stateOf(row)] || "info";
    },
    // 下架：只改启用状态，不删除记录
    offlineHandle(id) {
      this.$confirm(`确定下架场次[id=${id}]吗？下架后不再启用，但不会删除记录。`, "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning"
      }).then(() => {
        this.$http({
          url: this.$http.adornUrl("/coupon/seckillsession/offline"),
          method: "post",
          params: this.$http.adornParams({ id })
        }).then(({ data }) => {
          if (data && data.code === 0) {
            this.$message({
              message: "下架成功",
              type: "success",
              duration: 1500,
              onClose: () => {
                this.getDataList();
              }
            });
          } else {
            this.$message.error(data.msg);
          }
        });
      });
    },
    // 取消：进行中才能取消，先停止中，30 分钟后已取消
    cancelHandle(id) {
      this.$confirm(`确定取消场次[id=${id}]吗？会先进入停止中，30 分钟后变为已取消。`, "提示", {
        confirmButtonText: "确定",
        cancelButtonText: "取消",
        type: "warning"
      }).then(() => {
        this.$http({
          url: this.$http.adornUrl("/coupon/seckillsession/cancel"),
          method: "post",
          params: this.$http.adornParams({ id })
        }).then(({ data }) => {
          if (data && data.code === 0) {
            this.$message({
              message: "已取消，进入 30 分钟停止中",
              type: "success",
              duration: 1500,
              onClose: () => {
                this.getDataList();
              }
            });
          } else {
            this.$message.error(data.msg);
          }
        });
      });
    },
    // 删除
    deleteHandle(id) {
      var ids = id
        ? [id]
        : this.dataListSelections.map(item => {
            return item.id;
          });
      this.$confirm(
        `确定对[id=${ids.join(",")}]进行[${id ? "删除" : "批量删除"}]操作? 会物理删除场次和关联记录。`,
        "提示",
        {
          confirmButtonText: "确定",
          cancelButtonText: "取消",
          type: "warning"
        }
      ).then(() => {
        this.$http({
          url: this.$http.adornUrl("/coupon/seckillsession/delete"),
          method: "post",
          data: this.$http.adornData(ids, false)
        }).then(({ data }) => {
          if (data && data.code === 0) {
            this.$message({
              message: "操作成功",
              type: "success",
              duration: 1500,
              onClose: () => {
                this.getDataList();
              }
            });
          } else {
            this.$message.error(data.msg);
          }
        });
      });
    }
  }
};
</script>
