use crate::error::CoreError;
use crate::model::validate_pid;

pub const EPG_BASE: &str = "https://capi.yangshipin.cn/api/yspepg/program/";

pub fn program_url(pid: &str, date_yyyymmdd: &str) -> Result<String, CoreError> {
    validate_pid(pid)?;
    validate_date(date_yyyymmdd)?;
    Ok(format!("{EPG_BASE}{pid}/{date_yyyymmdd}"))
}

fn validate_date(date: &str) -> Result<(), CoreError> {
    if date.len() != 8 || !date.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(CoreError::InvalidDate);
    }
    let month: u32 = date[4..6].parse().map_err(|_| CoreError::InvalidDate)?;
    let day: u32 = date[6..8].parse().map_err(|_| CoreError::InvalidDate)?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return Err(CoreError::InvalidDate);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_epg_url() {
        assert_eq!(
            program_url("600152137", "20261119").unwrap(),
            "https://capi.yangshipin.cn/api/yspepg/program/600152137/20261119"
        );
    }

    #[test]
    fn rejects_bad_date() {
        assert!(program_url("600152137", "2026-11-19").is_err());
        assert!(program_url("600152137", "20261319").is_err());
    }
}
