#!/bin/sh
# Renders the ACL file from environment secrets, then starts Redis with AOF persistence.
# Each service user is restricted to its own key patterns / Pub/Sub channels.
set -eu
umask 077
cat > /tmp/users.acl <<ACL
user default off
user admin on >${REDIS_ADMIN_PASSWORD} ~* &* +@all
user presence on >${REDIS_PRESENCE_PASSWORD} ~presence:* ~gateway:* ~connection:* resetchannels -@all +@read +@write +@keyspace +@scripting +@connection +info -flushall -flushdb -keys -debug -config
user wsgateway on >${REDIS_WSGATEWAY_PASSWORD} ~connection:* ~gateway:* resetchannels &gw:* &typing -@all +@read +@write +@keyspace +@scripting +@pubsub +@connection +info -flushall -flushdb -keys -debug -config
user fanout on >${REDIS_FANOUT_PASSWORD} ~conversation:* resetchannels -@all +@read +@write +@keyspace +@connection +info -flushall -flushdb -keys -debug -config
user delivery on >${REDIS_DELIVERY_PASSWORD} %R~connection:* resetchannels &gw:* -@all +@read +publish +@connection +info -keys -debug -config
user apigw on >${REDIS_APIGW_PASSWORD} ~request_rate_limiter.* ~rl:* resetchannels -@all +@read +@write +@scripting +@connection +info +time -flushall -flushdb -keys -debug -config
ACL
exec redis-server --aclfile /tmp/users.acl --appendonly yes --appendfsync everysec \
  --save 300 10 --maxmemory 256mb --maxmemory-policy noeviction --protected-mode no
