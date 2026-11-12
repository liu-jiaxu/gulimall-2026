<!-- ============================================================
  商品三级分类管理页面
  功能说明：
    1. 以树形结构展示商品分类（一级 / 二级 / 三级）
    2. 勾选节点后，可对前两级节点进行新增（Append）操作
    3. 勾选叶子节点后，可对选中的分类进行批量删除（Delete）
  数据来源：/product/category/list/tree 接口
============================================================= -->
<template>
  <div>
    <!-- 拖拽开关：控制是否开启拖拽 -->
    <el-switch v-model="draggable" active-text="开启拖拽" inactive-text="关闭拖拽"></el-switch>
    <!-- 批量保存按钮：开启拖拽后，把本次拖拽的所有修改一次性保存 -->
    <el-button v-if="draggable" size="small" round @click="batchSave">批量保存</el-button>

    <!-- 分类树组件
       :data                    树形数据（menus）
       :props                   节点字段映射（children 子节点、name 显示名称）
       :expand-on-click-node    点击节点是否展开（false：不因点击展开）
       :show-checkbox           是否显示复选框
       node-key                 节点唯一标识字段（catId）
       ref                      组件引用名（用于调用树组件方法）
       :default-expanded-keys   默认展开的节点 key（catId）列表
       :draggable               是否可拖拽（由拖拽开关 draggable 控制）
       :allow-drop              拖拽过程中判断某位置是否允许放置
       @node-drag-start         拖拽开始时触发（清空上次提示）
       @node-drag-end           拖拽结束时触发（打印信息 + 提示拒绝原因）
       @node-drop               拖拽成功放置后触发（收集需要批量更新的节点数据） -->
    <el-tree :data="menus" :props="defaultProps" :expand-on-click-node="false" :show-checkbox="true" node-key="catId"
      ref="menuTree" :default-expanded-keys="expandedKey" :draggable="draggable" :allow-drop="allowDrop"
      @node-drag-start="handleDragStart" @node-drag-end="handleDragEnd" @node-drop="handleDrop">
      <!-- 自定义节点内容插槽：node 为树节点对象，data 为节点对应的数据 -->
      <span class="custom-tree-node" slot-scope="{ node, data }">
        <!-- 显示当前节点的分类名称 -->
        <span>{{ node.label }}</span>
        <span>
          <!-- 新增按钮：仅当节点被勾选 且 为前两级（level <= 2）时显示 -->
          <el-button v-if="node.checked && node.level <= 2" type="text" size="mini" @click="() => append(data)">
            Append
          </el-button>
          <!-- 删除按钮：仅当节点被勾选 且 为叶子节点（无子分类）时显示 -->
          <el-button v-if="node.checked && node.childNodes.length === 0" type="text" size="mini"
            @click="() => remove()">
            Delete
          </el-button>
          <!--编辑按钮-->
          <el-button v-if="node.checked" type="text" size="mini" @click="() => edit(data)">
            Edit
          </el-button>
        </span>
      </span>
    </el-tree>

    <!-- 新增分类对话框
         title          对话框标题
         :visible.sync  控制对话框显示/隐藏（与 dialogVisible 双向绑定） -->
    <el-dialog :title="title" :visible.sync="dialogVisible" width="30%" :close-on-click-modal="false">
      <!-- 新增分类的表单，模型为 category -->
      <el-form :model="category">
        <!-- 分类名称输入框，双向绑定 category.name -->
        <el-form-item label="分类名称">
          <el-input v-model="category.name" autocomplete="off"></el-input>
        </el-form-item>
        <!-- 图标输入框，双向绑定 category.icon -->
        <el-form-item label="图标">
          <el-input v-model="category.icon" autocomplete="off"></el-input>
        </el-form-item>
        <!-- 计量单位输入框，双向绑定 category.productUnit -->
        <el-form-item label="计量单位(个、件...)">
          <el-input v-model="category.productUnit" autocomplete="off"></el-input>
        </el-form-item>
        <!-- 排序序号输入框，双向绑定 category.sort -->
        <el-form-item label="排序序号（0-任意，值越大越靠前）">
          <el-input v-model="category.sort" autocomplete="off"></el-input>
        </el-form-item>
      </el-form>
      <!-- 对话框底部操作按钮区 -->
      <span slot="footer" class="dialog-footer">
        <!-- 取消按钮：关闭对话框，不提交 -->
        <el-button @click="dialogVisible = false">取 消</el-button>
        <!-- 确定按钮：提交新增分类 -->
        <el-button type="primary" @click="sumbitData">确 定</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
// 这里是组件的脚本逻辑部分，可以导入其他文件（组件、工具 js、json、图片等）

// 导出当前组件的配置对象
export default {
  // 局部注册的子组件（当前页面暂无子组件）
  components: {},

  // 组件响应式数据
  data() {
    return {
      // 分类树数据（后端返回的树形结构）
      menus: [],

      // 对话框标题（新增时"添加分类"，修改时"修改分类"）
      title: "",
      // 对话框类型：add 表示新增，edit 表示修改
      dialogType: "",

      // 新增分类的表单数据（字段与数据库 pms_category 表对应）
      // name：分类名称；parentCid：父分类 id；catLevel：层级；showStatus：是否显示；sort：排序
      category: { name: "", parentCid: 0, catLevel: 0, showStatus: 1, sort: 0, catId: null, icon: "", productUnit: "" },
      // 新增分类对话框是否显示（true 显示，false 隐藏）
      dialogVisible: false,

      // 默认展开的节点 key（catId）数组，新增/删除后用于展开指定分类
      expandedKey: [],

      // 拖拽被拒绝时待提示的消息（拖拽结束后一次性弹出，避免拖拽过程反复提示）
      dropMessage: "",
      // 拖拽节点的层级（用于判断拖拽后是否超过三级分类）
      draggingLevel: 0,

      // 记录本次拖拽需要批量更新的节点数据
      updateNodes: [],
      // 批量保存后需要展开的父菜单 id 列表
      pCid: [],
      // 拖拽开关（true 开启拖拽）
      draggable: false,

      // 树节点字段映射配置
      defaultProps: {
        // 子节点字段名
        children: 'children',
        // 节点显示名称字段名
        label: 'name'
      }
    }
  },

  // 组件方法
  methods: {
    // 从后端获取商品分类树形数据
    getMenus() {
      // 发起请求：调用商品分类列表（树形）接口
      this.$http({
        url: this.$http.adornUrl('/product/category/list/tree'),
        method: 'get'
      }).then(({ data }) => {
        console.log(data);
        // 请求成功：把返回的分类树数据存入 menus
        this.menus = data.data
        console.log('成功了获取到菜单数据....', data.data)
      }).catch((error) => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      });
    },

    // 对话框"确定"按钮的统一入口：根据对话框类型分发到新增或修改方法
    sumbitData() {
      if (this.dialogType == "add") {
        this.addCategory();
      } else if (this.dialogType == "edit") {
        this.editCategory();
      }
    },

    // 点击某个节点的 Append 按钮后触发：打开新增对话框并预填父分类信息
    append(data) {
      console.log("append", data);
      this.dialogType = "add"
      this.title = "添加分类"
      // 显示新增分类对话框
      this.dialogVisible = true;
      // 记录新分类的父分类 id（表示在该节点下新增子分类）
      this.category.parentCid = data.catId;
      // 计算新分类的层级：当前节点层级 + 1
      this.category.catLevel = data.catLevel * 1 + 1;

      // 其余属性改为默认值，防止修改操作赋值影响
      this.category.name = ""
      this.category.catId = null
      this.category.sort = 0
      this.category.icon = ""
      this.category.productUnit = ""
      this.category.showStatus = 1
    },

    // 点击对话框"确定"按钮后触发：提交新增分类请求
    addCategory() {
      console.log("提交的数据", this.category);
      // 发起请求：调用商品分类保存接口
      this.$http({
        url: this.$http.adornUrl("/product/category/save"),
        method: "post",
        data: this.$http.adornData(this.category, false),
      }).then(() => {
        // 保存成功：弹出成功提示
        this.$message({
          message: "添加成功",
          type: "success",
        });
        // 刷新出新的菜单（重新拉取分类树）
        this.getMenus();
        // 设置需要默认展开的菜单（展开新分类的父分类）
        this.expandedKey = [this.category.parentCid];
        // 关闭新增分类对话框
        this.dialogVisible = false;
      }).catch(() => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      })
    },

    // 批量删除被勾选且为叶子节点的分类
    remove() {
      // 收集被勾选且是叶子节点（无子分类）的节点，以 {catId, name} 对象数组存储
      const removeList = []
      // 遍历所有被勾选节点的 id
      this.$refs.menuTree.getCheckedKeys().forEach(id => {
        // 根据 id 获取对应的树节点对象
        const checkedNode = this.$refs.menuTree.getNode(id)
        // 只统计叶子节点（没有子分类的节点才允许删除）
        if (checkedNode && checkedNode.childNodes.length === 0) {
          // 记录该节点的 id 与名称
          removeList.push({ catId: id, name: checkedNode.data.name })
        }
      })
      // 接口请求所需的纯 id 数组（从 removeList 中提取）
      const catIds = removeList.map(item => item.catId)
      // 弹出二次确认框，提示用户将删除的分类数量
      this.$confirm(`确定要删除选中的 ${catIds.length} 个分类吗?`, '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      }).then(() => {
        // 用户点击"确定"后，发起批量删除请求
        this.$http({
          url: this.$http.adornUrl('/product/category/delete'),
          method: 'post',
          data: this.$http.adornData(catIds, false)
        }).then(({ data }) => {
          console.log(data);
          // 根据后端返回的 code 判断删除是否成功
          if (data && data.code === 0) {
            // 删除成功：弹出成功提示
            this.$message({
              message: '删除成功',
              type: 'success'
            }).catch((error) => {
              console.error(error);
              const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
              this.$message.error(msg);
            });// 删除成功：重新拉取分类树以刷新页面数据
            this.getMenus()
            // 删除成功：遍历所有被删节点，收集需要展开的父/祖父节点 key
            const expandKeys = []
            removeList.forEach(item => {
              const node = this.$refs.menuTree.getNode(item.catId)
              if (node && node.parent) {
                const parent = node.parent
                // 父节点下是否还有未被删除的子节点（即该节点是否有同级子节点）
                const hasSibling = parent.childNodes.some(n => catIds.indexOf(n.data.catId) === -1)
                if (hasSibling) {
                  // 有同级子节点：展开父节点，展示到这些子节点
                  expandKeys.push(parent.data.catId)
                } else if (parent.parent && parent.parent.data.catId !== undefined) {
                  // 无同级子节点（父节点下已删光）：展开祖父节点，展示到上一级父节点
                  expandKeys.push(parent.parent.data.catId)
                }
              }
            })
            // 过滤无效 key 并去重，设置默认展开节点
            this.expandedKey = [...new Set(expandKeys.filter(k => k !== undefined && k !== null))]
            // 删除成功：打印本次删除的分类信息（id + name）
            console.log('removeInfo', removeList)
          } else {
            // 删除失败：弹出后端返回的错误信息
            this.$message({
              message: data.msg,
              type: 'error'
            })
          }
          // 无论成败，打印后端返回的完整结果
          console.log('removeResult', data)
        })
      }).catch(() => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);

      })
    },

    // 点击某个节点的 Edit 按钮后触发：打开修改对话框并回显该分类的原始数据
    edit(data) {
      console.log("要修改的数据", data)
      this.dialogType = "edit"
      this.title = "修改分类"
      this.dialogVisible = true

      // 发送请求获取最新数据再修改，避免脏读
      this.$http({
        url: this.$http.adornUrl(`/product/category/info/${data.catId}`),
        method: 'get',
      }).then(({ data }) => {
        console.log(data);
        console.log("回显数据", data)
        // parentCid: 0, catLevel: 0, showStatus: 1, 
        this.category.name = data.data.name
        this.category.catId = data.data.catId
        this.category.sort = data.data.sort
        this.category.icon = data.data.icon
        this.category.productUnit = data.data.productUnit
        // 部分数据需要设置原值防止修改
        this.category.parentCid = data.data.parentCid
        this.category.catLevel = data.data.catLevel
        this.category.showStatus = data.data.showStatus
      }).catch((error) => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      });
    },

    // 提交修改分类请求（通过 update 接口更新该分类数据）
    editCategory() {
      console.log("修改的数据", this.category);
      // 发起请求：调用商品分类更新接口
      this.$http({
        url: this.$http.adornUrl("/product/category/update"),
        method: "post",
        data: this.$http.adornData(this.category, false),
      }).then(() => {
        // 保存成功：弹出成功提示
        this.$message({
          message: "修改成功",
          type: "success",
        });
        // 刷新出新的菜单（重新拉取分类树）
        this.getMenus();
        // 设置需要默认展开的菜单（展开新分类的父分类）
        this.expandedKey = [this.category.parentCid];
        // 关闭修改分类对话框
        this.dialogVisible = false;
      }).catch(() => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      })
    },

    /** 
     * 树节点拖拽时的允许拖拽判断函数
     * @param {Object} draggingNode - 被拖动的节点对象
     * @param {Object} dropNode - 被拖放的目标节点对象
     * @param {String} type - 拖放类型（before、after、inner）
     * @return {Boolean} - 是否允许拖拽（true 允许，false 不允许）
     */
    allowDrop(draggingNode, dropNode, type) {
      // 1.判断被拖动节点及目标节点最小层级相加是否大于 3（即拖动后是否超过三级分类）
      this.draggingLevel = this.findChildNodeLevel(draggingNode)
      if (type === 'inner') {
        // 拖放类型为 inner（拖动到目标节点内部）
        if (this.draggingLevel + dropNode.level > 3) {
          // 仅记录拒绝原因，不弹窗（避免拖拽过程中反复提示）
          this.dropMessage = "拖动后超过三级分类，禁止拖拽！"
          return false;
        }
      } else {
        // 拖放类型为 before/after（拖动到目标节点前/后）
        // dropNode.parent 可能为 null（目标是根节点），此时按 0 层处理
        const parentLevel = dropNode.parent ? dropNode.parent.level : 0
        if (this.draggingLevel + parentLevel > 3) {
          // 仅记录拒绝原因，不弹窗（避免拖拽过程中反复提示）
          this.dropMessage = "拖动后超过三级分类，禁止拖拽！"
          return false;
        }
      }
      // 允许放置时清空待提示消息
      this.dropMessage = ""
      return true;
    },

    // 拖拽开始：清空待提示消息，避免上一次拖拽的提示残留
    handleDragStart() {
      // 重置拖拽层级
      this.draggingLevel = 0
      // 清空上一次拖拽可能残留的待提示消息
      this.dropMessage = ""
    },

    // 拖拽结束：一次性打印拖拽信息并提示拒绝原因，避免拖拽过程中反复触发
    handleDragEnd(draggingNode, dropNode, dropType) {
      // 拖拽结束后一次性打印节点信息与层级
      console.log("拖拽节点", draggingNode, "目标节点", dropNode)
      console.log("拖拽类型", dropType)
      console.log("拖拽节点层级", this.draggingLevel)
      // dropNode 可能为 null（拖拽到树外部或未放到有效节点上），需做空值保护
      if (dropNode) {
        if (dropType === 'inner') {
          console.log("目标节点层级", dropNode.level)
          console.log("拖拽后层级", this.draggingLevel + dropNode.level)
        } else {
          // dropNode.parent 可能为 null（目标是根节点），此时按 0 层处理
          const parentLevel = dropNode.parent ? dropNode.parent.level : 0
          console.log("目标节点父节点层级", parentLevel)
          console.log("拖拽后层级", this.draggingLevel + parentLevel)
        }
      } else {
        console.log("拖拽未放置到有效节点（拖拽已取消）")
      }
      // 一次性提示拖拽被拒绝的原因，不再拖拽过程中反复弹窗
      if (this.dropMessage) {
        this.$message({
          message: this.dropMessage,
          type: "warning"
        });
        this.draggingLevel = 0
        this.dropMessage = ""
      }
    },

    /**
     * 计算树节点的层级深度，只能读取最多三层树形结构
     * @param {Object} node - 树节点对象
     * @return {Number} - 节点的层级深度（1、2 或 3）
     */
    findChildNodeLevel(node) {
      // 无子节点（叶子）：子树深度为 1
      if (!node || !node.childNodes || node.childNodes.length === 0) {
        return 1; // 如果没有子节点，返回 1
      }
      // 遍历子节点，若任一子节点还有子节点（有孙）：子树深度为 3
      for (let i = 0; i < node.childNodes.length; i++) {
        const childNode = node.childNodes[i];
        if (childNode && childNode.childNodes && childNode.childNodes.length > 0) {
          return 3; // 如果有3级子节点，返回 3
        }
      }
      // 有子节点但子节点无子节点：子树深度为 2
      return 2; // 如果没有3级子节点，但有2级子节点，返回 2
    },

    /**
     * 计算树节点的子树深度，可读取任意层级树形结构
     * @param {Object} node - 树节点对象
     * @return {Number} - 子树深度
     */
    findChildNodeLevel2(node) {
      if (!node) return 1
      // 从 node 出发，遍历整棵子树，找出最深节点的 level
      let maxLevel = node.level
      const stack = node.childNodes ? [...node.childNodes] : []
      while (stack.length > 0) {
        const cur = stack.pop()
        maxLevel = Math.max(maxLevel, cur.level)
        if (cur.childNodes && cur.childNodes.length > 0) {
          stack.push(...cur.childNodes)
        }
      }
      // 子树深度 = 最深节点层级 - 自身层级 + 1
      return maxLevel - node.level + 1
    },

    // 拖拽完成（node-drop）触发：收集本次拖拽需要批量更新的节点数据
    handleDrop(draggingNode, dropNode, dropType, ev) {
      // console.log("handleDrop: ", draggingNode, dropNode, dropType);
      // 1、当前节点最新父节点的id
      let pCid = 0;
      // 拖拽后的兄弟节点列表：拖到两侧用目标节点父节点的子节点，拖到内部用目标节点的子节点
      let sibings = null;
      if (dropType == "before" || dropType == "after") {
        // 拖成目标节点的同级：父节点为 dropNode 的父节点
        pCid = dropNode.parent.data.catId == undefined ? 0 : dropNode.parent.data.catId;
        sibings = dropNode.parent.childNodes;
      } else {
        // 拖成目标节点的子节点：父节点为 dropNode 自身
        pCid = dropNode.data.catId;
        sibings = dropNode.childNodes;
      }

      // 2、当前拖拽节点的最新顺序
      for (let i = 0; i < sibings.length; i++) {
        if (sibings[i].data.catId == draggingNode.data.catId) {
          // 如果遍历的是当前正在拖拽的节点
          let catLevel = draggingNode.level;
          if (sibings[i].level != draggingNode.level) {
            // 当前节点的层级发生变化
            catLevel = sibings[i].level;
            // 修改他子节点的层级
            this.updateChildNodeLevel(sibings[i]);
          }
          // sort 用倒序（length-i-1）：sort 值越大越靠前，与后端排序语义保持一致
          this.updateNodes.push({ catId: sibings[i].data.catId, sort: sibings.length - i - 1, parentCid: pCid, catLevel: catLevel });
        } else {
          // 兄弟节点：只记录 catId 和最新排序（同样倒序）
          this.updateNodes.push({ catId: sibings[i].data.catId, sort: sibings.length - i - 1 });
        }
      }

      // 记录本次拖拽的父节点 id，用于批量保存后展开对应菜单
      this.pCid.push(pCid);
      console.log(this.pCid)

    },

    // 递归收集子节点的最新层级（拖拽后父节点层级变化，子节点层级需同步更新）
    updateChildNodeLevel(node) {
      if (node.childNodes.length > 0) {
        for (let i = 0; i < node.childNodes.length; i++) {
          // 遍历子节点，记录 catId 和最新层级
          var cNode = node.childNodes[i].data;
          this.updateNodes.push({ catId: cNode.catId, catLevel: node.childNodes[i].level });
          // 递归处理子节点的子节点
          this.updateChildNodeLevel(node.childNodes[i]);
        }
      }
    },

    // 点击批量保存按钮：一次性提交本次拖拽收集的所有节点修改
    batchSave() {
      this.$http({
        url: this.$http.adornUrl("/product/category/update/sort"),
        method: "post",
        data: this.$http.adornData(this.updateNodes, false),
      }).then(({ data }) => {
        console.log(data);
        // 保存成功：弹出提示
        this.$message({ message: "菜单顺序修改成功", type: "success" });
        // 刷新出新的菜单
        this.getMenus();
        // 设置需要默认展开的菜单
        this.expandedKey = this.pCid;
        // 展开之后清空pCid，避免下次拖拽累积
        this.pCid = [];
      }).catch((error) => {
        console.error(error);
        const msg = (error && error.response && error.response.data && error.response.data.msg) || (error && error.message) || "请求失败";
        this.$message.error(msg);
      });
      // 清空本次收集的数据，避免越拖越多
      this.updateNodes = [];
    },
  },

  // 计算属性（当前无）
  computed: {},

  // 侦听器：监控 data 中的数据变化（当前无）
  watch: {},

  // 生命周期钩子 - created：组件实例创建完成后触发
  created() {
    // 页面初始化时，拉取分类树数据
    this.getMenus()
  },

  // 生命周期钩子 - mounted：组件挂载到 DOM 后触发
  mounted() {

  },

  // 生命周期钩子 - beforeCreate：组件实例创建之前触发
  beforeCreate() { },
  // 生命周期钩子 - beforeMount：组件挂载之前触发
  beforeMount() { },
  // 生命周期钩子 - beforeUpdate：数据更新之前触发
  beforeUpdate() { },
  // 生命周期钩子 - updated：数据更新之后触发
  updated() { },
  // 生命周期钩子 - beforeDestroy：组件销毁之前触发
  beforeDestroy() { },
  // 生命周期钩子 - destroyed：组件销毁完成后触发
  destroyed() { },
  // 生命周期钩子 - activated：页面被 keep-alive 缓存激活时触发
  activated() { }
}
</script>
<!-- 组件局部样式（scoped：仅作用于当前组件） -->
<style scoped></style>
