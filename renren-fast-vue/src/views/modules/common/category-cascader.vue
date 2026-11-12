<template>
  <!-- 
使用说明：
1）、引入category-cascader.vue
2）、语法：<category-cascader :catelogPath.sync="catelogPath"></category-cascader>
    解释：
      catelogPath：指定的值是cascader初始化需要显示的值，应该和父组件的catelogPath绑定;
          由于有sync修饰符，所以cascader路径变化以后自动会修改父的catelogPath，这是结合子组件this.$emit("update:catelogPath",v);做的
      -->
  <div class="category-cascader">
    <el-cascader ref="cascader" filterable clearable placeholder="试试搜索：手机" v-model="paths" :options="categorys"
      :props="setting"></el-cascader>
    <!-- 隐藏的测量元素：用与输入框相同的字体测量文字宽度 -->
    <span ref="measure" class="category-cascader__measure"></span>
  </div>
</template>

<script>
//这里可以导入其他文件（比如：组件，工具js，第三方插件js，json文件，图片文件等等）
//例如：import 《组件名称》 from '《组件路径》';

export default {
  //import引入的组件需要注入到对象中才能使用
  components: {},
  //接受父组件传来的值
  props: {
    catelogPath: {
      type: Array,
      default() {
        return [];
      }
    }
  },
  data() {
    //这里存放数据
    return {
      setting: {
        value: "catId",
        label: "name",
        children: "children"
      },
      categorys: [],
      paths: this.catelogPath
    };
  },
  watch: {
    catelogPath(v) {
      this.paths = this.catelogPath;
    },
    paths(v) {
      this.$emit("update:catelogPath", v);
      //通过 pubsub-js 广播；PubSub 已在 main.js 全局注册，判空仅为容错
      if (this.PubSub && this.PubSub.publish) {
        this.PubSub.publish("catPath", v);
      }
      //选择完成后等界面更新再调整宽度
      this.$nextTick(() => {
        this.$nextTick(() => this.resizeInput());
      });
    }
  },
  //方法集合
  methods: {
    getCategorys() {
      this.$http({
        url: this.$http.adornUrl("/product/category/list/tree"),
        method: "get"
      }).then(({ data }) => {
        this.categorys = data.data;
        console.log("category-cascader.getCategorys - categorys", this.categorys);
        //等级联数据渲染完成后再按当前文字调整一次宽度
        this.$nextTick(() => this.resizeInput());
      }).catch((error) => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      });
    },
    //让级联输入框根据文字自动伸缩宽度：初始宽度不变，文字变长时自动加宽
    resizeInput() {
      const cascaderEl = this.$refs.cascader && this.$refs.cascader.$el;
      if (!cascaderEl) return;
      //在输入框内输入（搜索）时也要实时调整宽度；事件绑定在根元素上，
      //即使内部 input 被重新渲染也能继续生效
      if (!cascaderEl.__autoWidthBound__) {
        cascaderEl.__autoWidthBound__ = true;
        cascaderEl.addEventListener("input", () => {
          this.$nextTick(() => this.resizeInput());
        });
      }
      //组件可见时记录初始宽度；之后文字变短也只回到该宽度（初始大小不变）
      if (cascaderEl.offsetWidth > 0 && !this._baseWidth) {
        this._baseWidth = cascaderEl.offsetWidth;
      }
      //还没记录到初始宽度（例如弹窗未打开、组件不可见）时保持原样
      if (!this._baseWidth) return;
      const base = this._baseWidth;
      const text = this.displayText(cascaderEl);
      const target = Math.min(
        Math.max(base, this.measureText(text) + this.extraWidth(cascaderEl)),
        //宽度上限，可按需调整
        Math.max(base, 800)
      );
      cascaderEl.style.width = target + "px";
    },
    //el-cascader 选中后的文字其实显示在覆盖层 .el-cascader__label 里，
    //原生 input 的 value 只在搜索输入时才有值，所以两种状态都要取到
    displayText(cascaderEl) {
      const inputEl = cascaderEl.querySelector(".el-input__inner");
      const labelEl = cascaderEl.querySelector(".el-cascader__label");
      if (inputEl && inputEl.value) {
        return inputEl.value;
      }
      if (labelEl && labelEl.textContent) {
        return labelEl.textContent.replace(/\s+/g, " ").trim();
      }
      return "";
    },
    //用与输入框相同字体的隐藏元素测量文字的宽度
    measureText(text) {
      const cascaderEl = this.$refs.cascader && this.$refs.cascader.$el;
      const inputEl = cascaderEl && cascaderEl.querySelector(".el-input__inner");
      const span = this.$refs.measure;
      if (!inputEl || !span) return 0;
      const cs = window.getComputedStyle(inputEl);
      span.style.fontFamily = cs.fontFamily;
      span.style.fontSize = cs.fontSize;
      span.style.fontWeight = cs.fontWeight;
      span.style.letterSpacing = cs.letterSpacing;
      span.textContent = text || "";
      return span.offsetWidth;
    },
    //文字左右需要的预留宽度：左侧内边距 + 右侧下拉箭头区域 + 边框 + 少量缓冲
    extraWidth(cascaderEl) {
      const inputEl = cascaderEl.querySelector(".el-input__inner");
      const cs = window.getComputedStyle(inputEl);
      const padLeft = parseFloat(cs.paddingLeft) || 15;
      const border =
        (parseFloat(cs.borderLeftWidth) || 0) +
        (parseFloat(cs.borderRightWidth) || 0);
      //右侧约 30px 留给下拉箭头/清除按钮，另加 4px 缓冲
      return padLeft + 30 + border + 4;
    }
  },
  //生命周期 - 创建完成（可以访问当前this实例）
  created() {
    this.getCategorys();
    this.$nextTick(() => this.resizeInput());
  },
  mounted() {
    //组件若放在 el-dialog 里，刚挂载时可能不可见（宽度为0），
    //短暂轮询，等它可见并记录到初始宽度后停止
    this._baseTimer = setInterval(() => {
      const cascaderEl = this.$refs.cascader && this.$refs.cascader.$el;
      if (cascaderEl && cascaderEl.offsetWidth > 0) {
        this.resizeInput();
        clearInterval(this._baseTimer);
        this._baseTimer = null;
      }
    }, 100);
    //最多轮询 5 秒，超时自动停止
    setTimeout(() => {
      if (this._baseTimer) {
        clearInterval(this._baseTimer);
        this._baseTimer = null;
      }
    }, 5000);
  },
  beforeDestroy() {
    if (this._baseTimer) {
      clearInterval(this._baseTimer);
      this._baseTimer = null;
    }
  }
};
</script>
<style scoped>
.category-cascader {
  position: relative;
}

.category-cascader__measure {
  position: absolute;
  top: 0;
  left: -9999px;
  visibility: hidden;
  white-space: pre;
  display: inline-block;
  pointer-events: none;
}
</style>