package com.example.negative;

/**
 * 遗留的「输入翻倍」工具。测试对它断言了两个互相矛盾的结果，
 * 任何实现都不可能让同一调用同时通过 —— 这正是负样本要验证的：
 * 有些「测试本身写错」的工程，Agent 改写源码也救不回来。
 */
public class Unsatisfiable {

    /** 把输入翻倍（遗留实现）。 */
    public int doubleIt(int x) {
        return x * 2;
    }
}
