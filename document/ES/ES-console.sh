############################################################
# Elasticsearch 8.18.8 检索练习脚本
# —— 对应《ES.md》第 4 章「初步检索」+ 第 5 章「进阶检索」
#
# 【这不是 bash 脚本，别放进终端跑】
#   它是 Kibana Dev Tools 控制台脚本，整份复制粘贴到控制台里执行。
#
# 【怎么用】
#   1. 浏览器打开 http://<your-vm-host-ip>:5601 ，用 elastic / <your-password> 登录
#   2. 左上角菜单 → Management → Dev Tools
#   3. 把本文件全部内容粘贴到左侧编辑器
#   4. 光标放在某个请求上，按 Ctrl + Enter 单条执行
#      想批量跑就框选多段再按 Ctrl + Enter
#
# 【Dev Tools 语法约定】
#   - 请求之间用空行隔开
#   - 请求【外面】的注释用 # 开头（也起分隔作用）
#   - 请求【体 JSON 内部】用 /* */ 块注释。不要用 #（JSON 解析失败）；
#     也不要用 //：Kibana 8.16+ 发送前会把请求体压平成一行，行注释会吞掉它后面的所有内容
#     （报 x_content_e_o_f_exception），块注释不受影响
#   - 认证由 Kibana 自动带上，不用写账号密码（这是它比 curl 方便的地方）
#
# 【⚠️ 本脚本不使用 bulk】
#   实测该 Kibana(8.18.8) 的 Console 无法解析 bulk(NDJSON) 的请求体 —— 会报：
#       Validation Failed: 1: no requests added
#       Expected one of GET/POST/PUT/DELETE/HEAD/PATCH
#   （同样的内容用 curl 发送则完全正常，所以是 Console 的解析缺陷，不是语法错误）
#   因此本脚本所有批量场景都改用【多条独立的 PUT 索引/_doc/id】实现。
#   bulk 的语法示例保留在 4.7 节，但已注释掉 —— 需要时用 curl 执行。
#
# 【_cat 输出格式】
#   所有 _cat 请求都加了 format=json，输出结构化 JSON 便于阅读。
#   注意：JSON 模式下 ?v（表头）参数不起作用；h= 仍可用于选择返回哪些字段。
#   其它接口（_search / _mapping 等）返回的本来就是 JSON，无需该参数。
#
# 【语法要点】
#   1. ES 里没有 type（类型）概念 —— 索引就是"表"，一个索引对应一张表
#      写文档的路径是 PUT /<索引名>/_doc/<文档ID>
#   2. bulk 请求体里不需要 _type 字段
#   3. 响应里也没有 _type 元数据
#
# 【版本】ES 8.18.8 / IK 8.18.8（单节点，副本数 0，集群状态 green）
#
# ⚠️⚠️ 本文件必须保持【LF 换行（Unix）】+【UTF-8 无 BOM】
#     若被转成 Windows 换行（CRLF），粘贴到控制台后每行末尾会多一个 \r，
#     Kibana 解析器会因此把 bulk 的请求体判定为空，报：
#         Validation Failed: 1: no requests added
#         Expected one of GET/POST/PUT/DELETE/HEAD/PATCH
#     用记事本/Word 打开另存最容易踩这个坑；VS Code 右下角可切换为 LF
############################################################


############################################################
# 第 0 步：准备测试数据（bank 索引）
# ----------------------------------------------------------
# 完整的银行账户测试数据有 1000 条。这里内置了 13 条
# 【精选样本】，覆盖后面所有查询示例所需的字段特征（mill/wallace/
# kings/各年龄段/各余额区间），好处是整个脚本自包含、粘贴即可跑通。
#
# 想用完整的 1000 条数据，先在终端执行（注意 Content-Type 是 x-ndjson）：
#   curl -u elastic:<your-password> -H "Content-Type: application/x-ndjson" \
#     -X POST "http://<your-vm-host-ip>:9200/bank/_bulk" \
#     --data-binary @es测试数据.json
# 数据下载：https://gitee.com/xlh_blog/common_content/raw/master/es测试数据.json
# 用 curl 导入过数据的话，可以跳过下面 13 条 PUT，直接从 4.1 开始跑。
############################################################

# 每次重跑前先清空，避免数据重复导致结果翻倍（生产环境别这么干！）
# 8.x 里 DELETE 索引名 就是删整张"表"，不再需要也不能指定类型
DELETE bank

# 建索引：显式指定分片数
# 单节点环境副本必须设 0 —— 副本分片无处分配会让集群变成 yellow
PUT bank
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 0
  }
}

# 导入样本数据
#
# 【为什么这里用 13 条独立 PUT 而不是 _bulk】
#   部分 Kibana 版本的 Console 对 bulk(NDJSON) 请求体解析异常，会报：
#       Validation Failed: 1: no requests added
#       Expected one of GET/POST/PUT/DELETE/HEAD/PATCH
#   单条 PUT 的普通 JSON 体在控制台里稳定可靠，十几条数据完全够用。
#   数据量大时请用上面注释里的 curl 导入（curl 传 NDJSON 没有这个问题）。
#
#   想练 bulk 语法的话，见 4.7 节；若控制台跑不通，直接用 curl 验证：
#   curl -u elastic:<密码> -H "Content-Type: application/x-ndjson" \
#     -X POST "http://<your-vm-host-ip>:9200/bank/_bulk" --data-binary @data.ndjson

# 1. mill=kings（address 含 Kings）
PUT bank/_doc/1
{"account_number":1,"balance":39225,"firstname":"Amber","lastname":"Duke","age":32,"gender":"M","address":"880 Holmes Lane","employer":"Pyrami","email":"amberduke@pyrami.com","city":"Brogan","state":"IL"}
PUT bank/_doc/6
{"account_number":6,"balance":5686,"firstname":"Hattie","lastname":"Bond","age":36,"gender":"M","address":"671 Bristol Street","employer":"Netagy","email":"hattiebond@netagy.com","city":"Dante","state":"TN"}
PUT bank/_doc/13
{"account_number":13,"balance":32838,"firstname":"Nanette","lastname":"Bates","age":28,"gender":"F","address":"789 Madison Street","employer":"Quility","email":"nanettebates@quility.com","city":"Nogal","state":"VA"}
# 2. 各年龄段 / 余额区间覆盖
PUT bank/_doc/18
{"account_number":18,"balance":4180,"firstname":"Dale","lastname":"Adams","age":33,"gender":"M","address":"467 Hutchinson Court","employer":"Boink","email":"daleadams@boink.com","city":"Orick","state":"MD"}
PUT bank/_doc/20
{"account_number":20,"balance":16418,"firstname":"Elinor","lastname":"Ratliff","age":36,"gender":"M","address":"282 Kings Place","employer":"Scentric","email":"elinorratliff@scentric.com","city":"Ribera","state":"WA"}
PUT bank/_doc/25
{"account_number":25,"balance":40540,"firstname":"Virginia","lastname":"Ayala","age":39,"gender":"F","address":"171 Putnam Avenue","employer":"Filodyne","email":"virginiaayala@filodyne.com","city":"Nicholson","state":"PA"}
PUT bank/_doc/32
{"account_number":32,"balance":48086,"firstname":"Dillard","lastname":"Mcpherson","age":34,"gender":"F","address":"702 Quentin Street","employer":"Quailcom","email":"dillardmcpherson@quailcom.com","city":"Veguita","state":"IN"}
PUT bank/_doc/37
{"account_number":37,"balance":18612,"firstname":"Mcgee","lastname":"Mooney","age":39,"gender":"M","address":"826 Fillmore Place","employer":"Reversus","email":"mcgeemooney@reversus.com","city":"Tooleville","state":"OK"}
PUT bank/_doc/44
{"account_number":44,"balance":34487,"firstname":"Aurelia","lastname":"Harding","age":37,"gender":"M","address":"502 Baycliff Terrace","employer":"Orbalix","email":"aureliaharding@orbalix.com","city":"Yardville","state":"DE"}
PUT bank/_doc/49
{"account_number":49,"balance":29104,"firstname":"Fulton","lastname":"Holt","age":23,"gender":"F","address":"451 Humboldt Street","employer":"Anocha","email":"fultonholt@anocha.com","city":"Sunriver","state":"RI"}
PUT bank/_doc/51
{"account_number":51,"balance":14074,"firstname":"Burton","lastname":"Meyers","age":39,"gender":"M","address":"348 Ember Court","employer":"Beachtown","email":"burtonmeyers@beachtown.com","city":"Newry","state":"KY"}
# 3. "mill road" 相关样本 —— 后面的 match / match_phrase / filter 示例靠这两条
PUT bank/_doc/99
{"account_number":99,"balance":47159,"firstname":"Reyna","lastname":"Wallace","age":28,"gender":"M","address":"990 Mill Road","employer":"Rice","email":"reynawallace@rice.com","city":"Trona","state":"CA"}
PUT bank/_doc/101
{"account_number":101,"balance":15000,"firstname":"Ilene","lastname":"Vinson","age":35,"gender":"M","address":"210 Mill Street","employer":"Tersa","email":"ilenevinson@tersa.com","city":"Kirk","state":"MD"}

# 确认导入结果：docs.count 应为 13，health 应为 green（副本 0 才可能是 green）
GET _cat/indices/bank?v&h=health,status,index,pri,rep,docs.count,store.size&format=json

# 看一眼索引的映射（动态映射自动推断出的字段类型，聚合示例要用到 gender.keyword）
GET bank/_mapping


############################################################
# 第 4 部分：初步检索
############################################################

############################################################
# 4.1 _cat 系列 —— 查看集群基本信息
#     _cat API 默认返回【纯文本表格】，加 ?v 显示表头（verbose）
#     本脚本统一加了 &format=json，改成输出结构化 JSON，看起来更清晰
#     （JSON 模式下 ?v 不起作用；h= 仍可用来选择返回哪些字段）
############################################################

# 查看所有节点
GET /_cat/nodes?v&format=json

# 查看 ES 健康状况：green=一切正常 / yellow=副本未分配（单节点常见）/ red=有主分片丢失
GET /_cat/health?v&format=json

# 查看主节点
GET /_cat/master?v&format=json

# 查看所有索引（相当于 MySQL 的 show databases）
# 注意：以 . 开头的系统索引（Kibana 自己建的）默认不显示
GET /_cat/indices?v&format=json

# 只想看某个索引，并指定返回哪些列，输出更清爽
GET /_cat/indices/bank?v&h=health,status,index,pri,rep,docs.count&format=json

# 连系统索引一起看（.internal.alerts-* 那些都是 Kibana 建的，属正常现象）
GET /_cat/indices?v&expand_wildcards=all&format=json


############################################################
# 4.2 索引一个文档（保存数据）
# ----------------------------------------------------------
# 【8.x 最重要的改动在这里】
#   URL 三段含义：PUT /<索引名>/_doc/<文档ID>
#   _doc 只是固定占位符，不是类型（ES 已没有 type 概念）
#   索引不存在时会自动创建（默认 action.auto_create_index: true）
############################################################

# ⚠️ customer 索引此时还不存在，下面这条会自动创建它 —— 默认是 1 主 1 副，
#    而单节点上副本分片无处分配，集群状态会从 green 掉成 yellow（数据不丢，只是副本没地方放）。
#    介意的话先执行这条显式建索引（副本设为 0），再执行下面的写入：
#
# PUT customer
# {
#   "settings": { "number_of_shards": 1, "number_of_replicas": 0 }
# }

# PUT：必须指定 id。id 已存在则覆盖（版本号 +1），不存在则新建
PUT customer/_doc/1
{
  "name": "John Doe"
}

# 响应解读（带下划线开头的都是元数据）：
#   "_index": "customer"     存在哪个索引下
#   "_id": "1"               文档 id
#   "_version": 1            版本号，每写一次 +1
#   "result": "created"      本次是新建；再次 PUT 同 id 会变成 "updated"
#   "_seq_no" / "_primary_term"   并发控制字段，见 4.4 乐观锁
# 注意：8.x 的响应里已经【没有 _type 字段】了


############################################################
# 4.3 查询文档
############################################################

GET customer/_doc/1

# 响应字段说明：
#   "_source"      ：你存进去的原始 JSON 内容
#   "found": true  ：查到了；id 不存在时返回 found: false
#   "_seq_no"      ：并发控制字段，每次更新 +1，用来做乐观锁
#   "_primary_term"：主分片重新分配（如重启）时会变化，同样用于乐观锁
#   "_version"     ：文档版本号


############################################################
# 4.4 乐观锁（并发控制）
# ----------------------------------------------------------
# 用法：URL 上带 ?if_seq_no=x&if_primary_term=y
#       只有当前文档的 seq_no 和 primary_term 与传入值完全一致时才允许修改，
#       否则返回 409 版本冲突。这是"乐观"的：不提前加锁，写的时候才校验。
############################################################

# 先看当前值（假设查出来 _seq_no=0、_primary_term=1）
GET customer/_doc/1
GET customer/_search

# 带条件更新：值对得上才改，改完 seq_no 会 +1
PUT customer/_doc/1?if_seq_no=0&if_primary_term=1
{
  "name": "John Doe"
}

# 再把上面这条原样发一次 —— 会失败，因为 seq_no 已经变了，乐观锁生效
# 预期返回：version_conflict_engine_exception
PUT customer/_doc/1?if_seq_no=0&if_primary_term=1
{
  "name": "John Doe"
}


############################################################
# 4.5 更新文档
# ----------------------------------------------------------
# 三种写法，区别如下：
#
#   POST /customer/_update/1 + {"doc":{...}}
#     → 会先跟原数据对比，内容一模一样就什么都不做，version 也不增加
#     → 适合"读多写少、偶尔更新"的场景，能省掉无意义的写操作
#
#   POST /customer/_doc/1  和  PUT /customer/_doc/1
#     → 不检查原数据，直接整体覆盖，每次都产生新版本
#     → 适合"写多读少"的场景
#
#   注意 _update 在 id 的前面：POST /<索引名>/_update/<文档ID>
############################################################

# 方式一：带 _update，会对比原值（此处和原值不同，正常更新）
POST customer/_update/1
{
  "doc": {
    "name": "John Doew"
  }
}

# 再把上一条原样发一次：内容没变，ES 直接跳过，version 不变
POST customer/_update/1
{
  "doc": {
    "name": "John Doew"
  }
}

# 方式二：POST 不指定 id → 自动生成 id 新增一条
POST customer/_doc
{
  "name": "Jane Doe"
}

# 方式三：PUT 必须带 id，不带会报错（PUT 的语义是"放到指定位置"，位置是必填的）
PUT customer/_doc/1
{
  "name": "John Doe",
  "age": 22
}


############################################################
# 4.6 删除文档 & 索引
# ----------------------------------------------------------
# 注意：ES 没有"删除类型"的操作（因为类型已经废弃了），
#       只有删除【文档】和删除【索引】两种粒度
############################################################

# 删除 id=1 的文档
DELETE customer/_doc/1

# 删除整个 customer 索引（相当于 DROP TABLE，数据全没，谨慎）
DELETE customer


############################################################
# 4.7 批量操作 —— bulk
# ----------------------------------------------------------
# 语法：两行一组，第一行是【动作 + 元数据】，第二行是【文档内容】
#
#   { "action": { "metadata" }}\n
#   { "request body" }\n
#
# 四种 action：
#   index  ：存在就覆盖，不存在就新建（最常用）
#   create ：只新建，id 已存在则报错
#   update ：局部更新，请求体要写成 {"doc":{...}}
#   delete ：删除，只要一行（没有文档内容行）
#
# 特点：动作之间互相独立 —— 某一条失败不影响其余的继续执行，
#       返回结果里会按发送顺序逐个给出每个动作的状态，便于排查哪条失败了
#
# ⚠️⚠️ 本机实测：该 Kibana(8.18.8) 的 Console 解析不了 bulk(NDJSON) 请求体，
#      会报下面这两个错（同样内容用 curl 发则正常，所以是 Console 缺陷不是语法错）：
#         Validation Failed: 1: no requests added
#         Expected one of GET/POST/PUT/DELETE/HEAD/PATCH
#      所以下面的 bulk 示例已【整体注释掉】，改用等价的单条命令演示。
#      要练 bulk 语法请用 curl（把请求体存成 .ndjson 文件）：
#         curl -u elastic:<密码> -H "Content-Type: application/x-ndjson" \
#           -X POST "http://<your-vm-host-ip>:9200/customer/_bulk" --data-binary @bulk.ndjson
############################################################

# ---- 用普通命令实现同样的批量效果（控制台里可靠）----

# bulk 的 index 动作（存在就覆盖，不存在就新建）等价于 PUT 索引/_doc/id
PUT customer/_doc/1
{"name": "John Doe"}
PUT customer/_doc/2
{"name": "Jane Doe"}

# bulk 的四种 action 与普通命令的对应关系：
#   bulk: {"delete":{...}}          →  DELETE website/_doc/123
#   bulk: {"create":{...}}          →  PUT website/_create/123   （只新建，已存在则报错）
#   bulk: {"index":{...}}           →  PUT website/_doc/2        （存在覆盖，不存在新建）
#   bulk: {"update":{...}}          →  POST website/_update/123  （局部更新，体为 {"doc":{...}}）
DELETE website/_doc/123
PUT website/_create/123
{"title":"my first blog post"}
PUT website/_doc/2
{"title":"my second blog post"}
POST website/_update/123
{"doc":{"title":"my updated blog post"}}

# ---- 以下为 bulk 原始写法（已注释，仅供查阅语法）----
#
# # 在 customer 索引下批量操作
# POST customer/_bulk
# {"index":{"_id":"1"}}
# {"name": "John Doe"}
# {"index":{"_id":"2"}}
# {"name": "Jane Doe"}
#
# # 全局 bulk：可跨索引操作，动作里用 _index 指定目标索引
# POST _bulk
# {"delete":{"_index":"website","_id":"123"}}
# {"create":{"_index":"website","_id":"123"}}
# {"title":"my first blog post"}
# {"index":{"_index":"website"}}
# {"title":"my second blog post"}
# {"update":{"_index":"website","_id":"123"}}
# {"doc":{"title":"my updated blog post"}}

# 响应解读：
#   "took"   ：耗时（毫秒）
#   "errors" ：false 表示所有动作都成功；true 则要逐个看 items 里哪个 status 不是 2xx
#   "items"  ：每个动作的结果，顺序与请求一致
#   注意 8.x 的响应里同样没有 _type 了


############################################################
# 第 5 部分：进阶检索
############################################################

############################################################
# 5.1 SearchAPI —— 两种检索方式
# ----------------------------------------------------------
#  ① URI 参数检索：把查询条件写在 URL 上（适合简单查询、命令行调试）
#  ② 请求体检索  ：把 DSL 写在 body 里（功能完整，实战几乎都用这种）
############################################################

# 检索 bank 下所有内容
GET bank/_search

# 方式一：URI 参数
#   q=*                    查询所有
#   sort=account_number:asc 按 account_number 升序
GET bank/_search?q=*&sort=account_number:asc
GET bank/_search?q=address:mill&sort=account_number:asc&size=3

# 方式二：请求体（推荐）
GET /bank/_search
{
  "query": { "match_all": {} },
  "sort": [
    { "account_number": "asc" },
    { "balance": "desc" }
  ]
}

# 响应字段解读：
#   took              ：本次搜索耗时（毫秒）
#   timed_out         ：是否超时
#   _shards           ：参与搜索的分片数，以及成功/失败数
#   hits.total.value  ：命中的文档总数
#   hits.max_score    ：相关性最高得分（match_all 时为 null，因为不评分）
#   hits.hits[]       ：命中文档列表
#   hits.sort         ：如果指定了排序，这里回显排序值
#   hits._score       ：单条文档的相关性得分
#
# 重要：搜索结果返回后本次请求就结束了，ES 不会在服务端保留游标（cursor）。
#       要翻页用 from + size（见 5.2），要深度翻页用 search_after。
#
# 提示：GET 带请求体在部分 HTTP 客户端里会被拒绝，换成 POST 即可，效果完全一样。


############################################################
# 5.2 Query DSL —— 查询领域特定语言
############################################################

# ---------- 5.2.1 基本语法格式 ----------
# 针对某个字段查询时的通用结构：
#
#   {
#     QUERY_NAME: {         ← 用哪种查询（match / term / range ...）
#       FIELD_NAME: {       ← 查哪个字段
#         ARGUMENT: VALUE,  ← 参数
#         ...
#       }
#     }
#   }
#
# 完整示例：分页 + 只取部分字段 + 排序
GET bank/_search
{
  "query": { "match_all": {} },   /* 查询条件：查所有 */
  "from": 0,   /* 从第几条开始（分页起点） */
  "size": 5,   /* 取几条 */
  "_source": ["balance", "age"],   /* 只返回这几个字段，减少网络传输 */
  "sort": [
    { "account_number": { "order": "desc" } }   /* 按 account_number 降序 */
  ]
}
# 说明：
#   - from + size 完成分页；sort 支持多字段，前序字段相等时才比较后续字段
#   - size 不写时默认返回 10 条
#   - 深度分页（from 很大）性能差，因为 ES 要在每个分片上各取 from+size 条再汇总


# ---------- 5.2.2 match —— 匹配查询（最常用） ----------
# 规则：非字符串字段做精确匹配；字符串字段做【全文检索】（会分词）
#       全文检索结果按相关性得分 _score 排序

# 非字符串：精确匹配 account_number == 20
GET bank/_search
{
  "query": {
    "match": {
      "account_number": "20"
    }
  }
}

# 字符串全文检索：address 含 "kings"（大小写不敏感，因为分词时会转小写）
GET bank/_search
{
  "query": {
    "match": {
      "address": "kings"
    }
  }
}

# 字符串全文检索：address 含 mill 或 road —— 只要命中其一即可，不是都要有
# 期望命中 "990 Mill Road" 和 "210 Mill Street" 两条
GET bank/_search
{
  "query": {
    "match": {
      "address": "mill road"
    }
  }
}


# ---------- 5.2.3 match_phrase —— 短语匹配 ----------
# 与 match 的区别：
#   match        ：把查询词拆开，命中任意一个即可（mill 或 road）
#   match_phrase ：查询词必须作为【连续短语】出现（必须连着出现 "mill road"）
#   xxx.keyword  ：不分词，必须整个字段值完全相等

# 只命中 "990 Mill Road" 这一条（"210 Mill Street" 不满足连续短语）
GET bank/_search
{
  "query": {
    "match_phrase": {
      "address": "mill road"
    }
  }
}

# 换成 .keyword 子字段 —— 必须完整匹配整个字段值
# 注意：address 动态映射后是 text，它的 keyword 子字段存的是完整原文
GET bank/_search
{
  "query": {
    "match_phrase": {
      "address.keyword": "990 Mill Road"
    }
  }
}


# ---------- 5.2.4 multi_match —— 多字段匹配 ----------
# 在多个字段里查同一个关键词，任意字段命中即可，且同样会分词
GET bank/_search
{
  "query": {
    "multi_match": {
      "query": "mill",
      "fields": ["state", "address"]
    }
  }
}

GET bank/_search
{
  "query": {
    "multi_match": {
      "query": "ca",
      "fields": ["state", "address"]
    }
  }
}


# ---------- 5.2.5 bool —— 复合查询 ----------
# 可以嵌套任意查询语句（包括其他 bool），从而表达复杂逻辑
#
#   must     ：必须全部满足，【影响相关性得分】
#   must_not ：必须都不满足，不贡献得分（相当于 filter）
#   should   ：满足更好，不满足也行；满足的得分更高
GET bank/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "gender": "M" } },   /* 性别必须是 M */
        { "match": { "address": "mill" } }   /* 地址必须含 mill */
      ],
      "must_not": [
        { "match": { "age": "18" } }   /* 年龄不能是 18 */
      ],
      "should": [
        { "match": { "lastname": "Wallace" } }   /* 姓 Wallace 的排前面（得分更高） */
      ]
    }
  }
}
# 期望命中 2 条（_id 99 和 101），其中 _id 99 因为 lastname=Wallace 得分更高、排在最前


# ---------- 5.2.6 filter —— 结果过滤 ----------
# 与 must 的区别只有一个：filter 不计算相关性得分
#
#   不评分的意义：ES 会自动跳过打分环节、并缓存过滤结果，因此比 must 更快。
#   凡是"只用来筛选、不关心相关度"的条件（价格区间、库存、状态）都该用 filter。
#
#   副作用：如果查询里只有 filter，所有命中文档的 _score 都是 0
GET bank/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "address": "mill" } }   /* 先全文检索出地址含 mill 的 */
      ],
      "filter": {
        "range": {
          "balance": {
            "gte": 10000,   /* 大于等于 */
            "lte": 20000   /* 小于等于 */
          }
        }
      }
    }
  }
}
# 即：地址含 mill 【并且】余额在 10000~20000 之间
# 期望只命中 _id 101（balance=15000）；_id 99 余额 47159 超范围被过滤掉


# ---------- 5.2.7 term —— 精确值查询 ----------
# match 用于全文检索字段（text），term 用于精确值字段（keyword、数值、布尔、日期）
#
#   原因：term 不做分词，拿查询值去倒排索引里【原样】比对。
#         对 text 字段用 term，因为 text 存的是分词后的词条，
#         除非你输入的恰好是某个完整词条，否则查不到 —— 这是最常见的踩坑点。
#
# 所以：年龄=28 用 term；地址含 "mill road" 用 match
GET bank/_search
{
  "query": {
    "bool": {
      "must": [
        { "term": { "age": { "value": "28" } } },   /* 年龄精确等于 28 */
        { "match": { "address": "990 Mill Road" } }   /* 地址全文检索 */
      ]
    }
  }
}
# 期望命中 _id 99（age=28 且地址是 990 Mill Road）


# ---------- 5.2.8 nested —— 嵌入式对象查询 ----------
# 【仅语法示例，跑不通是正常的】
#   下面用的是商品索引 gulimall_product 的 attrs 字段（mapping 里 type: nested）。
#   该索引在本脚本里【没有】创建（见《商品上架》一章），所以直接执行会报
#   index_not_found_exception。这里只作语法参考，看懂结构即可。
#
# 【为什么数组里的对象必须用 nested 查】
#   普通查询查的是【扁平化】之后的字段 —— 数组里每个对象的属性会被拆散、各存各的：
#       user.name = ["aaa", "bbb"]
#       user.addr = ["ccc", "ddd"]
#   能看出 {aaa, ddd} 这个组合其实并不存在，但扁平化之后它们落在同一篇文档里，
#   查 name=aaa 且 addr=ddd 竟然能命中 —— 这就是嵌套对象必须用 nested 的原因。
#   nested 把数组里每个对象存成【独立的隐藏文档】，从根本上杜绝这种张冠李戴。
GET /gulimall_product/_search
{
  "query": {
    "nested": {
      "path": "attrs",   /* 必填：声明要钻进哪个 nested 字段 */
      "query": {   /* 里面写正常 DSL，但字段名要用 attrs. 全路径前缀 */
        "bool": {
          "must": [
            { "match": { "attrs.attrValue": "青金" } }
          ]
        }
      }
    }
  }
}
# 四个要点：
#   1. path 必填，指明钻进哪个 nested 字段
#   2. 内部字段必须写【全路径】attrs.attrValue；只写 attrValue 查不到
#   3. 嵌套对象在底层是独立隐藏文档，所以【普通 match 直接查 attrs.attrValue 一条都命中不了】
#      —— 不是"结果不准"，而是"查不到"
#   4. 要求【同一个】attrs 对象同时满足多个条件时，必须写进【同一次】nested；
#      拆成两个 nested 会变成"不同对象各满足一个"，语义完全不同
#
# 想看是哪个嵌套对象命中的：在 nested 里加 "inner_hits": {}
#   响应会多出 hits.hits[].inner_hits.attrs.hits，列出命中的嵌套文档
# 打分：nested 默认 score_mode: avg，可改成 sum / min / max / none
#
# 注：attrValue 在 mapping 里是 keyword（不分词），所以这里的 match 等价于 term 精确匹配，
#     必须是完整值"青金"，写"青"查不到（原理同 5.2.7）


# ---------- 5.2.9 aggregations —— 聚合 ----------
# 聚合 = SQL 的 GROUP BY + 聚合函数。
# 特点：一次请求里既能返回 hits（命中文档），也能返回 aggregations（统计结果），
#       不用发多次请求，避免网络往返。
#
# 常用聚合类型：
#   terms ：按字段值分组（相当于 GROUP BY）
#   avg / sum / min / max / cardinality(去重计数) ：各种统计
#
# 关键技巧：聚合时通常写 "size": 0 —— 只要统计结果，不要命中的文档明细

# 例1：查 address 含 mill 的人，看年龄分布 + 平均年龄 + 平均余额（但不看明细）
GET bank/_search
{
  "query": {
    "match": { "address": "Mill" }
  },
  "aggs": {
    "ageAgg": {   /* 聚合名，随便起，结果里用它取值 */
      /* age相同的分组统计数量 */
      "terms": {
        "field": "age",
        "size": 10   /* 取前 10 个分组 */
      }
    },
    "ageAvg": {
      "avg": { "field": "age" }
    },
    "balanceAvg": {
      "avg": { "field": "balance" }
    }
  },
  "size": 0   /* 不返回文档明细，只要聚合结果 */
}
# 响应里会看到：
#   hits.total.value  命中几条
#   hits.hits         [] 空数组（因为 size=0）
#   aggregations.ageAgg.buckets[]  每个年龄值有多少人
#   aggregations.ageAvg.value      平均年龄
#   aggregations.balanceAvg.value  平均余额


# 例2：子聚合 —— 先按年龄分组，再求每个年龄段的平均余额
# 结构是 aggs / 聚合名 / aggs / 子聚合名，子聚合基于父聚合的结果继续算
GET bank/_search
{
  "query": { "match_all": {} },
  "aggs": {
    "ageAgg": {
      "terms": { "field": "age", "size": 100 },
      "aggs": {   /* 与 terms 并列，表示"在这个分组内部再算" */
        "ageBalanceAvg": {
          "avg": { "field": "balance" }
        }
      }
    }
  },
  "size": 0
}


# 例3：复杂子聚合 —— 每个年龄段里，分别算 M / F 的平均余额，以及该年龄段总体平均余额
# 注意 gender.keyword：text 字段不能直接用于聚合！
#   text 会被分词成多个词条，聚合没有意义，ES 也会直接拒绝。
#   动态映射会为 text 字段自动生成一个 keyword 子字段（存完整原文），
#   聚合/排序/精确匹配都用 .keyword 那个。
GET bank/_search
{
  "query": { "match_all": {} },
  "aggs": {
    "ageAgg": {
      "terms": { "field": "age", "size": 100 },
      "aggs": {
        "genderAgg": {
          "terms": { "field": "gender.keyword" },   /* 按性别分组 */
          "aggs": {
            "balanceAvg": {
              "avg": { "field": "balance" }   /* 该性别的平均余额 */
            }
          }
        },
        "ageBalanceAvg": {
          "avg": { "field": "balance" }   /* 该年龄段不分性别的平均余额 */
        }
      }
    }
  },
  "size": 0
}


############################################################
# 5.3 Mapping 字段映射
############################################################

# 查看索引的映射定义
GET bank/_mapping

# ---------- 创建索引并指定映射 ----------
# mappings 下面直接就是 properties
PUT my_index
{
  "mappings": {
    "properties": {
      "age": {
        "type": "integer"
      },
      "email": {
        "type": "keyword"   /* 不分词，用于精确匹配、聚合、排序 */
      },
      "name": {
        "type": "text"   /* 全文检索：写入时分词，检索时也分词匹配 */
      }
    }
  }
}

# 查看刚建的映射
GET my_index/_mapping

# ---------- 添加新字段 ----------
# 已有字段的映射【不能修改】（改了会让已建索引的数据不一致），
# 但可以往 mapping 里追加新字段
PUT my_index/_mapping
{
  "properties": {
    "employee-id": {
      "type": "keyword",
      "index": false   /* 不可被检索（不建索引），节省空间 */
    }
  }
}

# ---------- 数据迁移 _reindex ----------
# 场景：需要改字段类型时，因为"映射不可改"，只能新建索引再迁数据
#
# source 里只需要写 index
#
# 语法（要求【目标索引已存在】）：
#   POST _reindex
#   {
#     "source": { "index": "源索引" },
#     "dest":   { "index": "目标索引" }
#   }
#
# 下面是完整可执行的迁移示例：把 bank 迁到映射更规范的 newbank

# ① 先建目标索引（显式写死字段类型，避免动态映射乱猜）
PUT newbank
{
  "mappings": {
    "properties": {
      "account_number": { "type": "long" },
      "address":        { "type": "text" },
      "age":            { "type": "integer" },
      "balance":        { "type": "long" },
      "city":           { "type": "keyword" },
      "email":          { "type": "keyword" },
      "employer":       { "type": "keyword" },
      "firstname":      { "type": "text" },
      "gender":         { "type": "keyword" },
      "lastname": {
        "type": "text",
        "fields": {
          "keyword": {
            "type": "keyword",
            "ignore_above": 256   /* 超过 256 字符就不建 keyword 子字段，避免过大 */
          }
        }
      },
      "state": { "type": "keyword" }
    }
  }
}

# ② 再执行迁移
POST _reindex
{
  "source": { "index": "bank" },
  "dest":   { "index": "newbank" }
}

# ③ 确认迁移结果：docs.count 应与 bank 一致
GET _cat/indices/bank,newbank?v&h=index,docs.count,store.size&format=json

GET newbank/_search


# ---------- 迁移收尾：删旧索引 + 把新索引"改名"回旧名 ----------
# _reindex 只负责【搬数据】。搬完之后旧索引还在、旧名字还被业务代码用着 ——
# 只做到这一步等于没迁。生产上换 mapping 的完整流程是【四步】：
#     ① 建新索引 → ② _reindex 迁数据 → ③ 校验 → ④ 删旧索引 + 换名
#
# ⚠️ 前提：ES 没有"重命名索引"的 API —— 索引名是索引的【物理标识】，创建之后改不了。
#    所谓"改名"是靠【别名(alias)】实现的：删掉旧索引，再建一个与旧索引【同名】的别名
#    指向新索引。对业务代码和 Kibana 来说名字一点没变，只是别名背后的物理索引换了一个。
#
# ③ 校验 —— 上面那条 GET _cat/indices/bank,newbank 就是对数，两边 docs.count 必须一致。
#    注意：看到 "failures": [] 【不等于】迁移成功，还要看 created / updated /
#    version_conflicts 三个计数。
#
# ④ 删旧 + 建同名别名
#    ⚠️ 这两个请求【不是原子的】—— DELETE 和 POST /_aliases 之间有一个极短空窗，
#       此刻索引不存在，任何写请求都会失败。
#       想彻底消除空窗，正确做法是【从一开始就让业务用别名】：首次建索引时就建好
#       gulimall_product 别名指向 _v1，以后换 mapping 只需在【一个】_aliases 请求里
#       同时 remove 旧 + add 新 —— 这一步才是真正原子的，物理索引名随便换，业务无感。
#    ⚠️ 下面以 bank → newbank 演示。真跑会把 bank 删掉，脚本前面的示例就不再可重复执行，
#       所以【保持注释】，练习时想验证再手动放开。
#
# DELETE bank
# POST /_aliases
# {
#   "actions": [
#     { "add": { "index": "newbank", "alias": "bank" } }
#   ]
# }
#
# 【写入也要走别名时，必须声明 is_write_index】
#   别名默认【只用于读】。本项目 EsConstant.PRODUCT_INDEX 配的就是商品索引名（见 EsConstant），
#   商品上架正是【往它写】的 —— 漏了这个参数会表现为上架接口直接报错。
#
# POST /_aliases
# {
#   "actions": [
#     {
#       "add": {
#         "index": "gulimall_product_v2",
#         "alias": "gulimall_product",
#         "is_write_index": true
#       }
#     }
#   ]
# }
#
# 【迁移期间的增量数据怎么办】
#   _reindex 是【一次性快照式】搬运，迁移过程中新增/修改的文档不会自动同步。三种应对：
#     ① 停写窗口 —— 迁移前停写入，迁完校验通过再放开（能停机时最简单，推荐）
#     ② 双写     —— 迁移期间同时写新旧两个索引，迁完切读、再停写旧（要求不停机）
#     ③ 补增量   —— 迁完再跑一次只迁"某时间点之后变更"的数据（数据带更新时间字段时用）
#
#   补增量靠 source.query 只取变更的那部分：
#
# POST _reindex
# {
#   "source": {
#     "index": "bank",
#     "query": { "range": { "updateTime": { "gte": "2026-09-24T00:00:00" } } }
#   },
#   "dest": { "index": "newbank" }
# }
#
# 【两个常用参数】
#   "conflicts": "proceed"      顶层；遇到版本冲突继续执行（默认一遇冲突就报错中断）
#   "op_type": "create"         dest 里；只在目标不存在该 id 时才写，【天然幂等】
#                               —— 补增量、重跑都不会产生重复版本
#   ?wait_for_completion=false  数据量大时异步执行，立刻返回 task id，
#                               再用 GET _tasks/<task_id> 轮询进度（千万级文档建议这么干）
#
# POST _reindex
# {
#   "conflicts": "proceed",
#   "source": { "index": "bank" },
#   "dest":   { "index": "newbank", "op_type": "create" }
# }
#
# 【为什么不用"迁回原索引名"替代别名】
#   理论上可以：删掉 bank，用新 mapping 建回同名索引，再把 newbank 的数据 reindex 回去。
#   但那样数据要搬两遍，而且旧索引一删就没法回滚了。别名方案只搬一遍，
#   出问题改回别名即可回滚 —— 生产一律推荐别名。


# 清理演示用的索引（按需执行）
# DELETE newbank
# DELETE my_index


############################################################
# 5.4 分词
############################################################
# 分词器（analyzer）把一段文本切成一个个 token（词元），并记录每个词的位置偏移，
# 供短语查询、近邻查询、高亮显示使用。
#
# 中文默认用 standard 分词器 —— 它按【单字】切，对中文非常不友好，
# 所以生产环境必须装 IK 分词器（本项目已装好 8.18.8 版本）。
############################################################

# ---------- standard 分词器（有问题的那种） ----------
# 英文按空格切分正常；中文会被切成一个个单字
POST _analyze
{
  "analyzer": "standard",
  "text": "Quick brown fox!"
}

# 试试中文，你会看到每个汉字一个 token —— 这就是必须装 IK 的原因
POST _analyze
{
  "analyzer": "standard",
  "text": "小米手机性价比很高"
}

# ---------- ik_smart：粗粒度分词 ----------
# 会做歧义消解，切出来的词较少
POST _analyze
{
  "analyzer": "ik_smart",
  "text": "我是中国人"
}
# 结果：我 / 是 / 中国人

# ---------- ik_max_word：细粒度分词 ----------
# 会把所有可能的词都切出来（穷尽组合），召回率高，适合【建索引】时用
POST _analyze
{
  "analyzer": "ik_max_word",
  "text": "我是中国人"
}
# 结果：我 / 是 / 中国人 / 中国 / 国人

# ---------- 实际效果对比 ----------
# 电商检索的典型用法：
#   建索引（写入商品时）用 ik_max_word —— 切得细，召回率高
#   查询时用 ik_smart —— 切得粗，精确度高
POST _analyze
{
  "analyzer": "ik_max_word",
  "text": "小米手机性价比很高"
}

# ---------- 自定义词库验证 ----------
# IK 的扩展词库配置在 plugins/analysis-ik/config/IKAnalyzer.cfg.xml
# 配置好远程词库（ext_dict / remote_ext_dict）后，新增的词会被识别为一个整体
# 例如把"尚硅谷"加入词库后：
POST _analyze
{
  "analyzer": "ik_max_word",
  "text": "尚硅谷不错"
}
# 结果会切出：尚硅谷 / 硅谷 / 不错
# 若没配自定义词库，"尚硅谷"会被拆成 尚 / 硅 / 谷
#
# 1./mydata/docker/es/plugins/analysis-ik/config新增自己的词库gulimall.dic
# 2.词库配置文件的位置（本项目已挂载到宿主机）：
#   /mydata/docker/es/plugins/analysis-ik/config/IKAnalyzer.cfg.xml
#   修改<entry key="ext_dict">gulimall.dic</entry>
# 3.改完词库需要重启 ES 容器生效：
#   docker compose restart elasticsearch8.18.8


############################################################
# 脚本结束
#
# 想清理本次练习产生的数据（不会影响 Kibana 自己的系统索引）：
#   DELETE bank
#   DELETE newbank
#   DELETE my_index
#   DELETE customer
############################################################
