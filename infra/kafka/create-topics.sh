#!/bin/bash
# One-shot: creates all SecureChat topics (idempotent). Partitions sized for the MVP; replication
# factor 1 because the local/EC2 MVP has a single broker (no replicated durability).
set -euo pipefail
BS=${KAFKA_BOOTSTRAP:-kafka:9092}
RF=${KAFKA_RF:-1}
T=/opt/kafka/bin/kafka-topics.sh
for i in $(seq 1 60); do $T --bootstrap-server "$BS" --list >/dev/null 2>&1 && break; echo "waiting for kafka ($i)"; sleep 2; done
WEEK=604800000
create() { # name partitions retention_ms [cleanup]
  $T --bootstrap-server "$BS" --create --if-not-exists --topic "$1" --partitions "$2" --replication-factor "$RF" \
     --config retention.ms="$3" --config min.insync.replicas=1 --config max.message.bytes=1048576 >/dev/null
  echo "topic $1"
}
create chat.message.accepted 12 $WEEK
create chat.message.fanout.requested 12 $WEEK
create chat.message.fanout.completed 6 $WEEK
create chat.message.delivery.requested 12 $WEEK
create chat.message.delivered 6 $WEEK
create chat.message.read 6 $WEEK
create chat.message.failed 3 $WEEK
create chat.notification.requested 3 86400000
create chat.presence.online 6 86400000
create chat.presence.offline 6 86400000
create chat.presence.changed 6 86400000
create chat.device.connected 6 86400000
create chat.device.disconnected 6 86400000
create chat.device.registered 3 $WEEK
create chat.device.revoked 3 $WEEK
create chat.user.registered 3 $WEEK
create chat.user.status.changed 3 $WEEK
create chat.conversation.created 6 $WEEK
create chat.group.member.added 6 $WEEK
create chat.group.member.removed 6 $WEEK
create chat.group.commit 6 $WEEK
create chat.keys.prekeys.low 3 86400000
create chat.deadletter 3 2592000000
echo "kafka topics ready"
