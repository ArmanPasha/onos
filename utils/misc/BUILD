COMPILE_DEPS = COMPILE + JACKSON + [
    "@kryo//jar",
    "@metrics_core//jar",
    "@netty//jar",
    "@netty_common//jar",
    "@objenesis//jar",
]

osgi_jar_with_tests(
    name = "onlab-misc",
    exclude_tests = [
        "org.onlab.graph.AbstractGraphPathSearchTest",
        "org.onlab.graph.GraphTest",
        "org.onlab.util.SlidingWindowCounterTest",
    ],
    visibility = ["//visibility:public"],
    deps = COMPILE_DEPS,
)
