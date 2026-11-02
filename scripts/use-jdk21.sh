#!/bin/sh
# Source this in one terminal only. It does not edit shell startup files or replace ~/bin/java.
#   . ./scripts/use-jdk21.sh
jdk=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
if [ ! -x "$jdk/bin/java" ]; then
  echo "Azul Zulu 21 is not installed at $jdk" >&2
  return 1 2>/dev/null || exit 1
fi
export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
