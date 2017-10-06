// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// The following only applies to changes made to this file as part of YugaByte development.
//
// Portions Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
// Helpers for dealing with the protobufs defined in wire_protocol.proto.
#ifndef YB_COMMON_WIRE_PROTOCOL_H
#define YB_COMMON_WIRE_PROTOCOL_H

#include <vector>

#include "yb/client/schema.h"
#include "yb/common/wire_protocol.pb.h"
#include "yb/gutil/endian.h"
#include "yb/util/cast.h"
#include "yb/util/status.h"
#include "yb/util/net/net_fwd.h"

namespace yb {

class ConstContiguousRow;
struct ColumnId;
class ColumnSchema;
class faststring;
class HostPort;
class RowBlock;
class RowBlockRow;
class RowChangeList;
class Schema;
class Slice;

// Convert the given C++ Status object into the equivalent Protobuf.
void StatusToPB(const Status& status, AppStatusPB* pb);

// Convert the given protobuf into the equivalent C++ Status object.
Status StatusFromPB(const AppStatusPB& pb);

// Convert the specified HostPort to protobuf.
Status HostPortToPB(const HostPort& host_port, HostPortPB* host_port_pb);

// Returns the HostPort created from the specified protobuf.
Status HostPortFromPB(const HostPortPB& host_port_pb, HostPort* host_port);

// Returns an Endpoint from HostPortPB.
CHECKED_STATUS EndpointFromHostPortPB(const HostPortPB& host_portpb, Endpoint* endpoint);

// Adds addresses in 'addrs' to 'pbs'. If an address is a wildcard (e.g., "0.0.0.0"),
// then the local machine's FQDN or its network interface address is used in its place.
CHECKED_STATUS AddHostPortPBs(const std::vector<Endpoint>& addrs,
                              google::protobuf::RepeatedPtrField<HostPortPB>* pbs);

// Simply convert the list of host ports into a repeated list of corresponding PB's.
CHECKED_STATUS HostPortsToPBs(const std::vector<HostPort>& addrs,
                              google::protobuf::RepeatedPtrField<HostPortPB>* pbs);

enum SchemaPBConversionFlags {
  SCHEMA_PB_WITHOUT_IDS = 1 << 0,
  SCHEMA_PB_WITHOUT_STORAGE_ATTRIBUTES = 1 << 1,
};

// Convert the specified schema to protobuf.
// 'flags' is a bitfield of SchemaPBConversionFlags values.
Status SchemaToPB(const Schema& schema, SchemaPB* pb, int flags = 0);

// Convert the specified schema to protobuf without column IDs.
Status SchemaToPBWithoutIds(const Schema& schema, SchemaPB *pb);

// Returns the Schema created from the specified protobuf.
// If the schema is invalid, return a non-OK status.
Status SchemaFromPB(const SchemaPB& pb, Schema *schema);

// Convert the specified column schema to protobuf.
// 'flags' is a bitfield of SchemaPBConversionFlags values.
void ColumnSchemaToPB(const ColumnSchema& schema, ColumnSchemaPB *pb, int flags = 0);

// Return the ColumnSchema created from the specified protobuf.
ColumnSchema ColumnSchemaFromPB(const ColumnSchemaPB& pb);

// Convert the given list of ColumnSchemaPB objects into a Schema object.
//
// Returns InvalidArgument if the provided columns don't make a valid Schema
// (eg if the keys are non-contiguous or nullable).
Status ColumnPBsToSchema(
  const google::protobuf::RepeatedPtrField<ColumnSchemaPB>& column_pbs,
  Schema* schema);

// Returns the required information from column pbs to build the column part of SchemaPB.
CHECKED_STATUS ColumnPBsToColumnTuple(
    const google::protobuf::RepeatedPtrField<ColumnSchemaPB>& column_pbs,
    std::vector<ColumnSchema>* columns , std::vector<ColumnId>* column_ids, int* num_key_columns);

// Extract the columns of the given Schema into protobuf objects.
//
// The 'cols' list is replaced by this method.
// 'flags' is a bitfield of SchemaPBConversionFlags values.
Status SchemaToColumnPBs(
  const Schema& schema,
  google::protobuf::RepeatedPtrField<ColumnSchemaPB>* cols,
  int flags = 0);

// Encode the given row block into the provided protobuf and data buffers.
//
// All data (both direct and indirect) for each selected row in the RowBlock is
// copied into the protobuf and faststrings.
// The original data may be destroyed safely after this returns.
//
// This only converts those rows whose selection vector entry is true.
// If 'client_projection_schema' is not NULL, then only columns specified in
// 'client_projection_schema' will be projected to 'data_buf'.
//
// Requires that block.nrows() > 0
void SerializeRowBlock(const RowBlock& block, RowwiseRowBlockPB* rowblock_pb,
                       const Schema* client_projection_schema,
                       faststring* data_buf, faststring* indirect_data);

// Rewrites the data pointed-to by row data slice 'row_data_slice' by replacing
// relative indirect data pointers with absolute ones in 'indirect_data_slice'.
// At the time of this writing, this rewriting is only done for STRING types.
//
// Returns a bad Status if the provided data is invalid or corrupt.
Status RewriteRowBlockPointers(const Schema& schema, const RowwiseRowBlockPB& rowblock_pb,
                               const Slice& indirect_data_slice, Slice* row_data_slice);

// Extract the rows stored in this protobuf, which must have exactly the
// given Schema. This Schema may be obtained using ColumnPBsToSchema.
//
// Pointers are added to 'rows' for each of the extracted rows. These
// pointers are suitable for constructing ConstContiguousRow objects.
// TODO: would be nice to just return a vector<ConstContiguousRow>, but
// they're not currently copyable, so this can't be done.
//
// Note that the returned rows refer to memory managed by 'rows_data' and
// 'indirect_data'. This is also the reason that 'rows_data' is a non-const pointer
// argument: the internal data is mutated in-place to restore the validity of
// indirect data pointers, which are relative on the wire but must be absolute
// while in-memory.
//
// Returns a bad Status if the provided data is invalid or corrupt.
Status ExtractRowsFromRowBlockPB(const Schema& schema,
                                 const RowwiseRowBlockPB& rowblock_pb,
                                 const Slice& indirect_data,
                                 Slice* rows_data,
                                 std::vector<const uint8_t*>* rows);

// Set 'leader_hostport' to the host/port of the leader server if one
// can be found in 'entries'.
//
// Returns Status::NotFound if no leader is found.
Status FindLeaderHostPort(const google::protobuf::RepeatedPtrField<ServerEntryPB>& entries,
                          HostPort* leader_hostport);

//----------------------------------- CQL value encode functions ---------------------------------
static inline void CQLEncodeLength(const int32_t length, faststring* buffer) {
  uint32_t byte_value;
  NetworkByteOrder::Store32(&byte_value, static_cast<uint32_t>(length));
  buffer->append(&byte_value, sizeof(byte_value));
}

// Encode a 32-bit length into the buffer. Caller should ensure the buffer size is at least 4 bytes.
static inline void CQLEncodeLength(const int32_t length, void* buffer) {
  NetworkByteOrder::Store32(buffer, static_cast<uint32_t>(length));
}

// Encode a CQL number (8, 16, 32 and 64-bit integer). <num_type> is the integer type.
// <converter> converts the number from machine byte-order to network order and <data_type>
// is the coverter's return type. The converter's input type <data_type> is unsigned while
// <num_type> may be signed or unsigned.
template<typename num_type, typename data_type>
static inline void CQLEncodeNum(
    void (*converter)(void *, data_type), const num_type val, faststring* buffer) {
  static_assert(sizeof(data_type) == sizeof(num_type), "inconsistent num type size");
  CQLEncodeLength(sizeof(num_type), buffer);
  data_type byte_value;
  (*converter)(&byte_value, static_cast<data_type>(val));
  buffer->append(&byte_value, sizeof(byte_value));
}

// Encode a CQL floating point number (float or double). <float_type> is the floating point type.
// <converter> converts the number from machine byte-order to network order and <data_type>
// is the coverter's input type. The converter's input type <data_type> is an integer type.
template<typename float_type, typename data_type>
static inline void CQLEncodeFloat(
    void (*converter)(void *, data_type), const float_type val, faststring* buffer) {
  static_assert(sizeof(float_type) == sizeof(data_type), "inconsistent floating point type size");
  const data_type value = *reinterpret_cast<const data_type*>(&val);
  CQLEncodeNum(converter, value, buffer);
}

static inline void CQLEncodeBytes(const std::string& val, faststring* buffer) {
  CQLEncodeLength(val.size(), buffer);
  buffer->append(val);
}

static inline void Store8(void* p, uint8_t v) {
  *static_cast<uint8_t*>(p) = v;
}

//--------------------------------------------------------------------------------------------------
// For collections the serialized length (size in bytes -- not number of elements) depends on the
// size of their (possibly variable-length) elements so cannot be pre-computed efficiently.
// Therefore CQLStartCollection and CQLFinishCollection should be called before and, respectively,
// after serializing collection elements to set the correct value

// Allocates the space in the buffer for writing the correct length later and returns the buffer
// position after (i.e. where the serialization for the collection value will begin)
static inline int32_t CQLStartCollection(faststring* buffer) {
  CQLEncodeLength(0, buffer);
  return static_cast<int32_t>(buffer->size());
}

// Sets the value for the serialized size of a collection by subtracting the start position to
// compute length and writing it at the right position in the buffer
static inline void CQLFinishCollection(int32_t start_pos, faststring* buffer) {
  // computing collection size (in bytes)
  int32_t coll_size = static_cast<int32_t>(buffer->size()) - start_pos;

  // writing the collection size in bytes to the length component of the CQL value
  int32_t pos = start_pos - sizeof(int32_t); // subtracting size of length component
  NetworkByteOrder::Store32(&(*buffer)[pos], static_cast<uint32_t>(coll_size));
}

//----------------------------------- CQL value decode functions ---------------------------------
#define RETURN_NOT_ENOUGH(data, sz)                         \
  do {                                                      \
    if (data->size() < (sz)) {                              \
      return STATUS(NetworkError, "Truncated CQL message"); \
    }                                                       \
  } while (0)

// Decode a CQL number (8, 16, 32 and 64-bit integer). <num_type> is the parsed integer type.
// <converter> converts the number from network byte-order to machine order and <data_type>
// is the coverter's return type. The converter's return type <data_type> is unsigned while
// <num_type> may be signed or unsigned.
template<typename num_type, typename data_type>
static inline CHECKED_STATUS CQLDecodeNum(
    size_t len, data_type (*converter)(const void*), Slice* data, num_type* val) {
  static_assert(sizeof(data_type) == sizeof(num_type), "inconsistent num type size");
  if (len != sizeof(num_type)) return STATUS_SUBSTITUTE(NetworkError, "unexpected number byte "
        "length: expected $0, provided $1", static_cast<int64_t>(sizeof(num_type)), len);
  RETURN_NOT_ENOUGH(data, sizeof(num_type));
  *val = static_cast<num_type>((*converter)(data->data()));
  data->remove_prefix(sizeof(num_type));
  return Status::OK();
}

// Decode a CQL floating point number (float or double). <float_type> is the parsed floating point
// type. <converter> converts the number from network byte-order to machine order and <data_type>
// is the coverter's return type. The converter's return type <data_type> is an integer type.
template<typename float_type, typename data_type>
static inline CHECKED_STATUS CQLDecodeFloat(
    size_t len, data_type (*converter)(const void*), Slice* data, float_type* val) {
  // Make sure float and double are exactly sizeof uint32_t and uint64_t.
  static_assert(sizeof(float_type) == sizeof(data_type), "inconsistent floating point type size");
  data_type bval = 0;
  RETURN_NOT_OK(CQLDecodeNum(len, converter, data, &bval));
  *val = *reinterpret_cast<float_type*>(&bval);
  return Status::OK();
}

static inline CHECKED_STATUS CQLDecodeBytes(size_t len, Slice* data, std::string* val) {
  RETURN_NOT_ENOUGH(data, len);
  *val = std::string(util::to_char_ptr(data->data()), len);
  data->remove_prefix(len);
  return Status::OK();
}

static inline uint8_t Load8(const void* p) {
  return *static_cast<const uint8_t*>(p);
}

#undef RETURN_NOT_ENOUGH

} // namespace yb
#endif  // YB_COMMON_WIRE_PROTOCOL_H
