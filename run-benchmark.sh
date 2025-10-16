#!/bin/bash
# 性能压测运行脚本

# 编译项目
echo "编译项目..."
mvn test-compile -q

# 运行压测
echo "开始压测..."
java --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
     --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
     -cp "target/classes:target/test-classes:$(mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)" \
     com.akakata.benchmark.SimpleLoadTest
