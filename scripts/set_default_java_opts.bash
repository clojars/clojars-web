# Shsred java options for util scripts

JAVA_OPTS=(
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:+ExitOnOutOfMemoryError
    -Xmx1g
    -Xms1g
    -Daws.region=us-east-2
)
