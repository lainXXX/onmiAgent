#!/bin/bash
# 设置 JDK21 并运行 Maven 命令

export JAVA_HOME="/d/Develop/JAVA/JDK/JDK21"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")"
mvn "$@"
