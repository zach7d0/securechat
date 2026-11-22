#!/bin/bash
# Enables password authentication + role-based authorization, then runs the stock entrypoint.
set -e
CFG=/etc/cassandra/cassandra.yaml
sed -i -E 's/^authenticator:.*/authenticator: PasswordAuthenticator/' "$CFG"
sed -i -E 's/^authorizer:.*/authorizer: CassandraAuthorizer/' "$CFG"
sed -i -E 's/^role_manager:.*/role_manager: CassandraRoleManager/' "$CFG"
# Cassandra 5 uses nested "authenticator: class_name:" blocks in some builds; handle both forms.
sed -i -E 's/^(\s+class_name\s*:\s*)(org\.apache\.cassandra\.auth\.)?AllowAllAuthenticator/\1org.apache.cassandra.auth.PasswordAuthenticator/' "$CFG"
sed -i -E 's/^(\s+class_name\s*:\s*)(org\.apache\.cassandra\.auth\.)?AllowAllAuthorizer/\1org.apache.cassandra.auth.CassandraAuthorizer/' "$CFG"
exec /usr/local/bin/docker-entrypoint.sh "$@"
