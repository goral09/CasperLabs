use super::alloc::collections::BTreeMap;
use super::alloc::string::String;
use super::alloc::vec::Vec;

pub trait ToBytes {
    fn to_bytes(&self) -> Vec<u8>;
}

pub trait FromBytes: Sized {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error>;
}

#[derive(Debug, PartialEq, Eq, Clone)]
pub enum Error {
    EarlyEndOfStream,
    FormattingError,
    LeftOverBytes,
}

pub fn deserialize<T: FromBytes>(bytes: &[u8]) -> Result<T, Error> {
    let (t, rem): (T, &[u8]) = FromBytes::from_bytes(bytes)?;
    if rem.is_empty() {
        Ok(t)
    } else {
        Err(Error::LeftOverBytes)
    }
}

fn safe_split_at(bytes: &[u8], n: usize) -> Result<(&[u8], &[u8]), Error> {
    if n > bytes.len() {
        Err(Error::EarlyEndOfStream)
    } else {
        Ok(bytes.split_at(n))
    }
}

impl ToBytes for u8 {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(1);
        result.push(*self);
        result
    }
}
impl FromBytes for u8 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        match bytes.split_first() {
            None => Err(Error::EarlyEndOfStream),
            Some((byte, rem)) => Ok((*byte, rem)),
        }
    }
}

impl ToBytes for i32 {
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
    }
}
impl FromBytes for i32 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut container: [u8; 4] = [0u8; 4];
        let (num_bytes, rem) = safe_split_at(bytes, 4)?;
        container.copy_from_slice(num_bytes);
        Ok((i32::from_le_bytes(container), rem))
    }
}

impl ToBytes for u32 {
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
    }
}
impl FromBytes for u32 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut container: [u8; 4] = [0u8; 4];
        let (num_bytes, rem) = safe_split_at(bytes, 4)?;
        container.copy_from_slice(num_bytes);
        Ok((u32::from_le_bytes(container), rem))
    }
}

impl ToBytes for u64 {
    fn to_bytes(&self) -> Vec<u8> {
        self.to_le_bytes().to_vec()
    }
}
impl FromBytes for u64 {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let mut container: [u8; 8] = [0u8; 8];
        let (num_bytes, rem) = safe_split_at(bytes, 8)?;
        container.copy_from_slice(num_bytes);
        Ok((u64::from_le_bytes(container), rem))
    }
}

impl FromBytes for Vec<u8> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<u8> = Vec::with_capacity(size as usize);
        let mut stream = rest;
        for _ in 0..size {
            let (t, rem): (u8, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<u8> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + size as usize);
        result.extend(size.to_bytes());
        result.extend(self);
        result
    }
}

impl FromBytes for Vec<i32> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<i32> = Vec::with_capacity(4 * size as usize);
        let mut stream = rest;
        for _ in 0..size {
            let (t, rem): (i32, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(t);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl<T: ToBytes> ToBytes for Option<T> {
    fn to_bytes(&self) -> Vec<u8> {
        match self {
            Some(v) => {
                let mut value = v.to_bytes();
                let mut result = Vec::with_capacity(4 + value.len());
                result.append(&mut 1u32.to_bytes());
                result.append(&mut value);
                result
            }
            // if the Option is empty then there
            // is no value to serialize, but we still
            // indicate the number of elements that need
            // to be deserialized.
            None => 0u32.to_bytes(),
        }
    }
}
impl<T: FromBytes> FromBytes for Option<T> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        match size {
            0 => Ok((None, rest)),
            1 => {
                let (t, rem): (T, &[u8]) = FromBytes::from_bytes(rest)?;
                Ok((Some(t), rem))
            }
            _ => Err(Error::FormattingError),
        }
    }
}

impl ToBytes for Vec<i32> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + 4 * size as usize);
        result.extend(size.to_bytes());
        result.extend(self.iter().flat_map(ToBytes::to_bytes));
        result
    }
}

impl FromBytes for Vec<Vec<u8>> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (length, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<Vec<u8>> = Vec::with_capacity(length as usize);
        let mut stream = rest;
        for _ in 0..length {
            let (v, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(v);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<Vec<u8>> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result: Vec<u8> = Vec::with_capacity(4 + size as usize);
        result.extend_from_slice(&size.to_bytes());
        for n in 0..size {
            result.extend_from_slice(&self[n as usize].to_bytes());
        }
        result
    }
}

impl FromBytes for Vec<String> {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (size, rest): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result: Vec<String> = Vec::with_capacity(size as usize);
        let mut stream = rest;
        for _ in 0..size {
            let (s, rem): (String, &[u8]) = FromBytes::from_bytes(stream)?;
            result.push(s);
            stream = rem;
        }
        Ok((result, stream))
    }
}

impl ToBytes for Vec<String> {
    fn to_bytes(&self) -> Vec<u8> {
        let size = self.len() as u32;
        let mut result = Vec::with_capacity(4);
        result.extend(size.to_bytes());
        let bytes = self.iter().flat_map(ToBytes::to_bytes);
        result.extend(bytes);
        result
    }
}
impl ToBytes for [u8; 32] {
    fn to_bytes(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(36);
        result.extend((32u32).to_bytes());
        result.extend(self);
        result
    }
}
impl FromBytes for [u8; 32] {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (bts, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        if bts.len() != 32 {
            Err(Error::FormattingError)
        } else {
            let mut array = [0u8; 32];
            array.copy_from_slice(&bts);
            Ok((array, rem))
        }
    }
}

impl ToBytes for String {
    fn to_bytes(&self) -> Vec<u8> {
        self.as_str().to_bytes()
    }
}
impl FromBytes for String {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (str_bytes, rem): (Vec<u8>, &[u8]) = FromBytes::from_bytes(bytes)?;
        let string = String::from_utf8(str_bytes).map_err(|_| Error::FormattingError)?;
        Ok((string, rem))
    }
}

impl ToBytes for () {
    fn to_bytes(&self) -> Vec<u8> {
        Vec::new()
    }
}
impl FromBytes for () {
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        Ok(((), bytes))
    }
}

impl<K, V> ToBytes for BTreeMap<K, V>
where
    K: ToBytes,
    V: ToBytes,
{
    fn to_bytes(&self) -> Vec<u8> {
        let num_keys = self.len() as u32;
        let bytes = self.iter().flat_map(|(k, v)| {
            let mut b = k.to_bytes();
            b.append(&mut v.to_bytes());
            b
        });

        let mut result = Vec::with_capacity(bytes.size_hint().0 + 4);
        result.append(&mut num_keys.to_bytes());
        result.extend(bytes);
        result
    }
}
impl<K, V> FromBytes for BTreeMap<K, V>
where
    K: FromBytes + Ord,
    V: FromBytes,
{
    fn from_bytes(bytes: &[u8]) -> Result<(Self, &[u8]), Error> {
        let (num_keys, rem): (u32, &[u8]) = FromBytes::from_bytes(bytes)?;
        let mut result = BTreeMap::new();
        let mut rest = rem;
        for _ in 0..num_keys {
            let (k, rem1): (K, &[u8]) = FromBytes::from_bytes(rest)?;
            let (v, rem2): (V, &[u8]) = FromBytes::from_bytes(rem1)?;
            result.insert(k, v);
            rest = rem2;
        }
        Ok((result, rest))
    }
}

impl ToBytes for str {
    fn to_bytes(&self) -> Vec<u8> {
        let bytes = self.as_bytes();
        let size = self.len();
        let mut result = Vec::with_capacity(size + 4);
        result.extend((size as u32).to_bytes());
        result.extend(bytes);
        result
    }
}
