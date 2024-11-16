# Shsred java options for util scripts

JAVA_OPTS=(
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:+ExitOnOutOfMemoryError
    -Xmx768m
    -Xms768m
    -Daws.region=us-east-2
)
