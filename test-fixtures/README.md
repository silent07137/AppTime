# 共享测试样例

UTF-8、LF、制表符分隔；时间单位为毫秒，起点为固定测试时钟的 0。不是生产或演示数据。

`android-switch-events.tsv`：A 60 秒 → B 30 秒 → A 10 秒；结束边界 110000，记录起点 0。预期见 `android-switch-expected.tsv`。Android core 测试实际读取这些文件；Windows 后续实现可消费相同样例或转换为对应前台切换输入。
