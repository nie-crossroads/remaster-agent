package com.example.unfixable.malformed;

// 这个文件是故意写坏的：它既不是合法的 Java，也不参与编译（位于 test/resources 下）。
// 用途是驱动「ANALYZE 阶段就解析失败」这条路径 —— 用来验证一个具体的判断：
// 源码都读不懂的时候，重试是没有意义的（换一次调用只会得到同样的结果），
// 系统应当立刻判定任务前提不成立并停手，而不是花三轮预算去试同一件事。
//
// 注意：把它放在 resources 目录下而不是 java 目录下，是为了让 legacy-unfixable
// 的另外那个反例（构建永远不过）不因为这个文件而改变失败原因 ——
// 两个反例的失败点必须彼此独立，才各自说明得了问题。

public class NotParseable {

    public void broken( {
        return
    }
}
