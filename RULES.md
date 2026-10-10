# 教务系统解析规则（插件）编写说明

规则是一个 JSON 文件，可通过「设置 → 数据 → 教务系统导入 → 导入规则文件」加载，同 id 会覆盖内置规则。

```json
{
  "id": "my-school-jw",            // 唯一标识
  "name": "某某大学教务（正方）",     // 显示名称
  "vendor": "zfsoft",              // 系统厂商，可选
  "version": 1,
  "author": "你的名字",
  "match": ["/jwglxt/", "xskbcx"], // URL 包含这些片段（或正则）时自动选用
  "hint": "登录后进入 课表查询 页面再点抓取", // 给用户的操作提示
  "script": "...JavaScript 函数体..."
}
```

## script 约定
- 在教务页面上下文中以 `async` 函数体执行，可使用 `document`、`fetch`（同源、自动带 Cookie）、`await`。
- 必须 `return` 一个对象：
```js
return {
  semesterStart: "2026-09-07",      // 可选，开学日期
  courses: [{
    name: "高等数学",                // 必填
    teacher: "王老师",               // 可选
    location: "教一 201",            // 可选
    day: 1,                          // 1=周一 … 7=周日
    startPeriod: 1, endPeriod: 2,    // 节次（或 periods:[1,2]）
    weekText: "1-16周(单)",          // 周次文本，由应用解析
    // 或 weeks: [1,3,5,7]           // 直接给出周次数组（优先）
  }]
};
```
- 抛出异常（`throw '原因'`）会把原因显示给用户。
- weekText 支持：`1-16周`、`1-8周,10-16周(双)`、`1,3,5周`、`第2-17周 单周`、`全周`。
- 不连续周次会自动拆成多条记录；单双周自动识别。

## 调试
在「教务系统导入」页面右上角可切换规则并查看脚本返回的原始错误信息；也可以先在电脑浏览器控制台中把 script 包在 `(async()=>{ ... })().then(console.log)` 里运行。

欢迎把适配好的规则文件分享给同学或提交到社区仓库。

内置规则源文件见 `app/src/main/assets/rules/`，欢迎提交 PR 增加学校适配。
