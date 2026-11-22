#!/usr/bin/env python3
"""Generates JSON Schemas (draft 2020-12) for event payloads. Re-run only to ADD a new version.

Existing <type>.vN.json files are never overwritten (compatibility rule); new event types or new
versions are written and must be added to schemas/CHECKSUMS by the contract test's update mode.
"""
import json, os, sys

U = {"type": "string", "format": "uuid"}
UN = {"type": ["string", "null"], "format": "uuid"}
T = {"type": "string", "format": "date-time"}
TN = {"type": ["string", "null"], "format": "date-time"}
S = {"type": "string", "minLength": 1}
SN = {"type": ["string", "null"]}
I = {"type": "integer"}
IN = {"type": ["integer", "null"]}
B = {"type": "boolean"}
UL = {"type": "array", "items": U}
ULN = {"type": ["array", "null"], "items": U}
ENV = {"type": "object", "required": ["deviceId", "signalType", "ciphertext"],
       "properties": {"deviceId": U, "signalType": {"type": "integer", "enum": [1, 3]}, "ciphertext": S}}

def obj(req, opt=None):
    props = dict(req); props.update(opt or {})
    return {"type": "object", "required": list(req.keys()), "properties": props, "additionalProperties": True}

receipt = obj({"messageId": U, "conversationId": U, "senderUserId": U, "recipientUserId": U,
               "recipientDeviceId": U, "status": {"type": "string", "enum": ["DELIVERED", "READ"]}, "at": T})
presence = obj({"userId": U, "online": B, "activeDevices": I, "at": T}, {"lastSeen": TN})
conn = obj({"userId": U, "deviceId": U, "gatewayId": S, "at": T})
membership = obj({"conversationId": U, "userIds": UL, "memberUserIds": UL, "membershipVersion": I, "epoch": I,
                  "at": T}, {"actorUserId": UN})
SCHEMAS = {
    "message.accepted": obj(
        {"messageId": U, "conversationId": U, "conversationType": {"enum": ["DIRECT", "GROUP"]}, "senderUserId": U,
         "senderDeviceId": U, "clientMessageId": U, "protocol": {"enum": ["SIGNAL", "MLS"]}, "messageType": S,
         "membershipVersion": I, "createdAt": T, "receivedAt": T, "persistedAt": T},
        {"epoch": IN, "envelopes": {"type": ["array", "null"], "items": ENV}, "ciphertext": SN}),
    "message.fanout.completed": obj(
        {"messageId": U, "conversationId": U, "expectedDevices": I, "deviceIds": UL, "completedAt": T},
        {"skippedDevices": {"type": ["array", "null"], "items": obj({"deviceId": U, "reason": S})}}),
    "message.delivery.requested": obj(
        {"deliveryId": U, "messageId": U, "conversationId": U, "senderUserId": U, "senderDeviceId": U,
         "recipientUserId": U, "recipientDeviceId": U, "protocol": {"enum": ["SIGNAL", "MLS"]}, "messageType": S,
         "ciphertext": S, "createdAt": T}, {"signalType": IN, "epoch": IN}),
    "message.delivered": receipt,
    "message.read": receipt,
    "message.failed": obj({"messageId": U, "recipientDeviceId": U, "reason": S, "at": T},
                          {"conversationId": UN, "recipientUserId": UN}),
    "notification.requested": obj({"recipientUserId": U, "recipientDeviceId": U, "messageId": U, "at": T}),
    "presence.changed": presence, "presence.online": presence, "presence.offline": presence,
    "device.connected": conn, "device.disconnected": conn,
    "device.registered": obj({"userId": U, "deviceId": U, "platform": S, "at": T}, {"deviceName": SN}),
    "device.revoked": obj({"userId": U, "deviceId": U, "at": T}, {"revokedBy": SN}),
    "user.registered": obj({"userId": U, "username": S, "at": T}, {"displayName": SN}),
    "user.status.changed": obj({"userId": U, "status": {"enum": ["ACTIVE", "SUSPENDED"]}, "at": T}),
    "conversation.created": obj({"conversationId": U, "type": {"enum": ["DIRECT", "GROUP"]}, "memberUserIds": UL,
                                 "createdBy": U, "at": T}),
    "group.member.added": membership, "group.member.removed": membership,
    "group.commit": obj({"conversationId": U, "epoch": I, "senderDeviceId": U, "memberUserIds": UL, "at": T}),
    "keys.prekeys.low": obj({"userId": U, "deviceId": U, "remainingPrekeys": I, "remainingKeyPackages": I}),
}
ENVELOPE = obj({"eventId": U, "eventType": S, "eventVersion": {"type": "integer", "minimum": 1},
                "timestamp": T, "aggregateId": S, "producer": S, "payload": {"type": "object"}},
               {"correlationId": SN, "traceId": SN})

out = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "schemas")
os.makedirs(out, exist_ok=True)
def write(name, schema):
    path = os.path.join(out, name)
    if os.path.exists(path):
        return
    schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "$id": "urn:securechat:" + name, **schema}
    with open(path, "w") as f:
        json.dump(schema, f, indent=2); f.write("\n")
    print("wrote", name)
write("envelope.v1.json", ENVELOPE)
for k, v in SCHEMAS.items():
    write(f"{k}.v1.json", v)
