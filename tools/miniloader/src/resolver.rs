/* SPDX-License-Identifier: MPL-2.0 */
//! The resolver channel: 360 bytes in, exactly 988 bytes out.
//!
//! These two numbers are confirmed on the Java side (`docs/HAL_PROTOCOL.md` §5):
//! `TwoyiResolverHal` reads a fixed 360-byte request, hands it to native
//! `nativeResolve`, and requires the reply to be **exactly** 988 bytes, otherwise
//! it throws `Invalid resolver response`. A connection serves one exchange and is
//! then closed.
//!
//! What the 360 and 988 bytes contain is still unknown, so this module models the
//! envelope only: lengths, read/write discipline, and the one-shot lifecycle.

use std::io::{Read, Write};

/// Fixed request size read by the host.
pub const RESOLVER_REQUEST_LEN: usize = 360;
/// Fixed response size the host must produce.
pub const RESOLVER_RESPONSE_LEN: usize = 988;

#[derive(Debug)]
pub enum ResolverError {
    Io(std::io::Error),
    /// The peer closed before the full request arrived.
    ShortRequest { got: usize },
    /// A resolver produced a reply of the wrong size.
    BadResponseLen { got: usize },
}

impl From<std::io::Error> for ResolverError {
    fn from(error: std::io::Error) -> Self {
        ResolverError::Io(error)
    }
}

impl std::fmt::Display for ResolverError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ResolverError::Io(error) => write!(formatter, "io: {error}"),
            ResolverError::ShortRequest { got } => {
                write!(formatter, "short request: {got} of {RESOLVER_REQUEST_LEN} bytes")
            }
            ResolverError::BadResponseLen { got } => {
                write!(formatter, "invalid resolver response: {got} bytes, want {RESOLVER_RESPONSE_LEN}")
            }
        }
    }
}

/// Anything that can turn a 360-byte request into a 988-byte response.
pub trait Resolver {
    fn resolve(&mut self, request: &[u8; RESOLVER_REQUEST_LEN]) -> Vec<u8>;
}

/// Serve one resolver exchange on `stream`, then return.
pub fn serve_once<S, R>(stream: &mut S, resolver: &mut R) -> Result<(), ResolverError>
where
    S: Read + Write,
    R: Resolver,
{
    let mut request = [0u8; RESOLVER_REQUEST_LEN];
    let mut filled = 0;
    while filled < RESOLVER_REQUEST_LEN {
        let count = stream.read(&mut request[filled..])?;
        if count == 0 {
            return Err(ResolverError::ShortRequest { got: filled });
        }
        filled += count;
    }

    let response = resolver.resolve(&request);
    if response.len() != RESOLVER_RESPONSE_LEN {
        return Err(ResolverError::BadResponseLen { got: response.len() });
    }
    stream.write_all(&response)?;
    stream.flush()?;
    Ok(())
}

/// The guest side: send exactly 360 bytes and require exactly 988 back.
pub fn request_once<S: Read + Write>(
    stream: &mut S,
    request: &[u8; RESOLVER_REQUEST_LEN],
) -> Result<Vec<u8>, ResolverError> {
    stream.write_all(request)?;
    stream.flush()?;
    let mut response = vec![0u8; RESOLVER_RESPONSE_LEN];
    let mut filled = 0;
    while filled < RESOLVER_RESPONSE_LEN {
        let count = stream.read(&mut response[filled..])?;
        if count == 0 {
            return Err(ResolverError::BadResponseLen { got: filled });
        }
        filled += count;
    }
    Ok(response)
}