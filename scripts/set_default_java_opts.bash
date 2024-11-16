# Shsred java options for util scripts

JAVA_OPTS=(
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:+ExitOnOutOfMemoryError
    -Xmx512m
    -Xms512m
    -Daws.region=us-east-2
)
