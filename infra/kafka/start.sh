#!/bin/sh
set -eu
/opt/kafka/bin/kafka-storage.sh format --ignore-formatted --cluster-id KRaftCreditLensLocal001 --config /opt/kafka/config/server.properties
exec /opt/kafka/bin/kafka-server-start.sh /opt/kafka/config/server.properties
