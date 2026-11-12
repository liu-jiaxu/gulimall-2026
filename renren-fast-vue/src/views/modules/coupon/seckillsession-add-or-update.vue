<template>
  <el-dialog
    :title="!dataForm.id ? '新增' : '修改'"
    :close-on-click-modal="false"
    :visible.sync="visible"
  >
    <el-form
      :model="dataForm"
      :rules="dataRule"
      ref="dataForm"
      @keyup.enter.native="dataFormSubmit()"
      label-width="120px"
    >
      <el-form-item label="场次名称" prop="name">
        <el-input v-model="dataForm.name" placeholder="场次名称"></el-input>
      </el-form-item>
      <el-form-item label="所属活动" prop="promotionId">
        <el-select v-model="dataForm.promotionId" clearable filterable
                   placeholder="可选，选择秒杀活动" style="width: 100%;">
          <el-option v-for="p in promotionList" :key="p.id"
                     :label="p.title + '（ID:' + p.id + '）'" :value="p.id"></el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="开始时间" prop="startTime">
        <el-date-picker type="datetime" placeholder="开始时间" v-model="dataForm.startTime"
                        value-format="yyyy-MM-dd HH:mm:ss" style="width: 100%;"
                        :picker-options="pickerOptions"></el-date-picker>
        <div style="color: #909399; font-size: 12px; line-height: 18px;">
          开始时间必须至少在 1 小时后，且不超过 30 天
        </div>
      </el-form-item>
      <el-form-item label="结束时间" prop="endTime">
        <el-date-picker type="datetime" placeholder="结束时间" v-model="dataForm.endTime"
                        value-format="yyyy-MM-dd HH:mm:ss" style="width: 100%;"
                        :picker-options="endPickerOptions"></el-date-picker>
        <div style="color: #909399; font-size: 12px; line-height: 18px;">
          结束时间不能超过 30 天（RabbitMQ 延时上限）
        </div>
      </el-form-item>
      <el-form-item label="启用状态" prop="status">
        <el-radio-group v-model="dataForm.status">
          <el-radio :label="1">启用</el-radio>
          <el-radio :label="0">未启用</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <span slot="footer" class="dialog-footer">
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="dataFormSubmit()">确定</el-button>
    </span>
  </el-dialog>
</template>

<script>
// RabbitMQ 延时插件的 x-delay 是 32 位毫秒值，最长约 49.7 天。
// 这里按业务取整到 30 天，留足安全余量，避免超长延时变成立即投递。
const MAX_SESSION_DAYS = 30;

function maxSessionEndTime() {
  const max = new Date();
  max.setHours(23, 59, 59, 999);
  max.setDate(max.getDate() + MAX_SESSION_DAYS);
  return max.getTime();
}

export default {
  data() {
    return {
      visible: false,
      pickerOptions: {
        // 禁用过去日期，同时限制不能超过 30 天
        disabledDate(time) {
          return time.getTime() < Date.now() - 24 * 60 * 60 * 1000
            || time.getTime() > maxSessionEndTime();
        }
      },
      endPickerOptions: {
        // 结束时间同样不能超过 30 天
        disabledDate(time) {
          return time.getTime() < Date.now() - 24 * 60 * 60 * 1000
            || time.getTime() > maxSessionEndTime();
        }
      },
      promotionList: [],
      dataForm: {
        id: 0,
        promotionId: null,
        name: "",
        startTime: "",
        endTime: "",
        status: 1,
        createTime: ""
      },
      dataRule: {
        name: [
          { required: true, message: "场次名称不能为空", trigger: "blur" }
        ],
        startTime: [
          { required: true, message: "开始时间不能为空", trigger: "blur" }
        ],
        endTime: [
          { required: true, message: "结束时间不能为空", trigger: "blur" }
        ]
      }
    };
  },
  methods: {
    init(id) {
      this.dataForm.id = id || 0;
      this.visible = true;
      this.loadPromotions();
      this.$nextTick(() => {
        this.$refs["dataForm"].resetFields();
        if (this.dataForm.id) {
          this.$http({
            url: this.$http.adornUrl(
              `/coupon/seckillsession/info/${this.dataForm.id}`
            ),
            method: "get",
            params: this.$http.adornParams()
          }).then(({ data }) => {
            if (data && data.code === 0) {
              this.dataForm.promotionId = data.seckillSession.promotionId;
              this.dataForm.name = data.seckillSession.name;
              this.dataForm.startTime = data.seckillSession.startTime;
              this.dataForm.endTime = data.seckillSession.endTime;
              this.dataForm.status = data.seckillSession.status == null
                ? 1 : Number(data.seckillSession.status);
              this.dataForm.createTime = data.seckillSession.createTime;
            }
          });
        }
      });
    },
    loadPromotions() {
      this.$http({
        url: this.$http.adornUrl("/coupon/seckillpromotion/list"),
        method: "get",
        params: this.$http.adornParams({page: 1, limit: 1000})
      }).then(({data}) => {
        this.promotionList = data && data.code === 0 && data.page ? data.page.list || [] : [];
      });
    },
    // 表单提交
    dataFormSubmit() {
      this.$refs["dataForm"].validate(valid => {
        if (valid) {
          const start = new Date(this.dataForm.startTime).getTime();
          const end = new Date(this.dataForm.endTime).getTime();
          if (!start || !end) {
            this.$message.error("请选择开始和结束时间");
            return;
          }
          if (start < Date.now() + 60 * 60 * 1000) {
            this.$message.error("秒杀开始时间必须至少在 1 小时后");
            return;
          }
          if (end <= start) {
            this.$message.error("结束时间必须晚于开始时间");
            return;
          }
          const maxTime = maxSessionEndTime();
          if (start > maxTime || end > maxTime) {
            this.$message.error(`开始时间和结束时间不能超过 ${MAX_SESSION_DAYS} 天`);
            return;
          }
          this.$http({
            url: this.$http.adornUrl(
              `/coupon/seckillsession/${!this.dataForm.id ? "save" : "update"}`
            ),
            method: "post",
            data: this.$http.adornData({
              id: this.dataForm.id || undefined,
              promotionId: this.dataForm.promotionId || undefined,
              name: this.dataForm.name,
              startTime: this.dataForm.startTime,
              endTime: this.dataForm.endTime,
              status: Number(this.dataForm.status)
              // createTime、seckillState 交给后端，前端不要传，
              // 否则 JSON 序列化成 ISO(2026-10-25T04:53:03.792Z)，后端按 yyyy-MM-dd HH:mm:ss 解析会 400
            })
          }).then(({ data }) => {
            if (data && data.code === 0) {
              this.$message({
                message: "操作成功",
                type: "success",
                duration: 1500,
                onClose: () => {
                  this.visible = false;
                  this.$emit("refreshDataList");
                }
              });
            } else {
              this.$message.error(data.msg);
            }
          });
        }
      });
    }
  }
};
</script>
