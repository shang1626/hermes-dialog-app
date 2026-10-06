# R8 规则（release 包专用）。
#
# 原则：能少写就少写。AGP 会自动为清单里声明的 Application / Activity / Service /
# Receiver 生成 keep 规则，Compose / OkHttp / Coil 各自带 consumer 规则，所以这里
# 只补本 App 真正需要的几条。
#
# 本仓实测无 Class.forName、无 java.lang.reflect、无 getIdentifier 按名取资源，
# 所以不需要「整包 keep」那种保底写法。

# 保留行号与源文件名：CrashLog 抓到的堆栈要靠它才有可读的行号，
# 全裁掉之后闪退记录里只剩一串无意义的 a.b.c。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留注解（AndroidX 一部分组件靠运行期读注解）
-keepattributes *Annotation*

# 服务与接收器：清单里已声明，AGP 也会保，这里再写一道防止被优化掉入口方法。
-keep class com.hermesapp.RunService { *; }
-keep class com.hermesapp.ReplyReceiver { *; }
-keep class com.hermesapp.MainActivity { *; }
-keep class com.hermesapp.HermesApplication { *; }

# 枚举的 values()/valueOf()：R8 在 aggressive 优化下可能删掉未被直接调用的枚举常量。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Kotlin 元数据：保住反射式读取（Compose 编译器插件等）不出意外。
-keep class kotlin.Metadata { *; }

# 不要因为找不到引用就打印一堆警告；保留必要的提示行。
-dontnote okhttp3.**
-dontnote coil.**