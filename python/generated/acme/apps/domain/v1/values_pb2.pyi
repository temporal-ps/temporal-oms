from acme.oms.v1 import values_pb2 as _values_pb2
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from typing import ClassVar as _ClassVar

DESCRIPTOR: _descriptor.FileDescriptor

class Errors(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ERRORS_UNSPECIFIED: _ClassVar[Errors]
    ERRORS_INVALID_ARGUMENTS: _ClassVar[Errors]
    ERRORS_ORDER_TIMED_OUT: _ClassVar[Errors]
    ERRORS_SERVICE_UNAVAILABLE: _ClassVar[Errors]
    ERRORS_UNAUTHORIZED: _ClassVar[Errors]
    ERRORS_CONFLICT: _ClassVar[Errors]
ERRORS_UNSPECIFIED: Errors
ERRORS_INVALID_ARGUMENTS: Errors
ERRORS_ORDER_TIMED_OUT: Errors
ERRORS_SERVICE_UNAVAILABLE: Errors
ERRORS_UNAUTHORIZED: Errors
ERRORS_CONFLICT: Errors
