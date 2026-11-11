#!/bin/bash

# 快速构建并运行 Trunkline 应用
# 适用于 Linux/Unix 环境

PORT=${1:-31080}
PROFILE=${2:-fast}

echo -e "\033[0;32m🚀 开始构建 Trunkline 应用...\033[0m"

# 检查 Maven 是否安装
if ! command -v mvn &> /dev/null; then
    echo -e "\033[0;31m❌ 未找到 Maven，请先安装 Maven\033[0m"
    exit 1
fi

echo -e "\033[0;32m✅ Maven 已安装\033[0m"

# 执行 Maven 构建
echo -e "\033[1;33m🔨 执行 mvn package -P$PROFILE ...\033[0m"
mvn package -P$PROFILE

if [ $? -ne 0 ]; then
    echo -e "\033[0;31m❌ 构建失败\033[0m"
    exit 1
fi

echo -e "\033[0;32m✅ 构建成功完成\033[0m"

# 查找构建好的 JAR 文件
JAR_FILE=$(find target -name "trunkline-*.jar" -type f | head -n 1)

if [ -z "$JAR_FILE" ]; then
    echo -e "\033[0;31m❌ 未找到构建好的 JAR 文件\033[0m"
    exit 1
fi

echo -e "\033[0;32m📦 找到 JAR 文件: $(basename $JAR_FILE)\033[0m"

# 运行应用
echo -e "\033[1;33m🏃 运行应用，端口: $PORT\033[0m"
echo -e "\033[0;36m🔗 访问地址: http://localhost:$PORT\033[0m"
echo -e "\033[0;36m⏹️  按 Ctrl+C 停止应用\033[0m"

java -Dserver.port=$PORT -jar "$JAR_FILE"