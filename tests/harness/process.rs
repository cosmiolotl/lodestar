//! The programs a cluster runs, each with its output in a log file.
//!
//! On Windows every program is put in a job object that ends it when this
//! process ends, however it ends, so that an interrupted run does not leave
//! servers running.

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use anyhow::{Context, bail};

pub(crate) struct Process {
    pub name: String,
    pub log: PathBuf,
    child: Mutex<Child>,
}

impl Process {
    pub fn spawn(name: &str, mut command: Command, log: &Path) -> anyhow::Result<Process> {
        let file = fs::File::create(log).with_context(|| format!("creating {}", log.display()))?;
        command.stdin(Stdio::null()).stdout(file.try_clone()?).stderr(file);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }
        let child = command
            .spawn()
            .with_context(|| format!("starting {name}: {command:?}"))?;
        #[cfg(windows)]
        job::adopt(&child);
        Ok(Process {
            name: name.to_owned(),
            log: log.to_path_buf(),
            child: Mutex::new(child),
        })
    }

    pub fn log_text(&self) -> String {
        fs::read(&self.log)
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_default()
    }

    /// The last lines of the log, to show with a failure.
    pub fn log_tail(&self, lines: usize) -> String {
        let text = self.log_text();
        let all: Vec<&str> = text.lines().collect();
        all[all.len().saturating_sub(lines)..].join("\n")
    }

    /// Waits for a line of the log that has `needle` in it, and returns it.
    pub fn wait_for_log(&self, needle: &str, timeout: Duration) -> anyhow::Result<String> {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(line) = self.log_text().lines().find(|l| l.contains(needle)) {
                return Ok(line.to_owned());
            }
            if let Some(status) = self.exit_status() {
                bail!(
                    "{} exited ({status}) before logging {needle:?}; see {}\n{}",
                    self.name,
                    self.log.display(),
                    self.log_tail(30)
                );
            }
            if Instant::now() >= deadline {
                bail!(
                    "{} did not log {needle:?} within {timeout:?}; see {}\n{}",
                    self.name,
                    self.log.display(),
                    self.log_tail(30)
                );
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    }

    /// How it exited, if it has.
    pub fn exit_status(&self) -> Option<ExitStatus> {
        self.child.lock().unwrap().try_wait().ok().flatten()
    }

    pub fn wait(&self, timeout: Duration) -> Option<ExitStatus> {
        let deadline = Instant::now() + timeout;
        loop {
            if let Some(status) = self.exit_status() {
                return Some(status);
            }
            if Instant::now() >= deadline {
                return None;
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    pub fn kill(&self) {
        let mut child = self.child.lock().unwrap();
        if child.try_wait().ok().flatten().is_none() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

impl Drop for Process {
    fn drop(&mut self) {
        self.kill();
    }
}

#[cfg(windows)]
mod job {
    use std::os::windows::io::AsRawHandle;
    use std::sync::OnceLock;

    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JobObjectExtendedLimitInformation,
        SetInformationJobObject,
    };

    struct Job(HANDLE);

    // The handle is only ever used to add processes to the job.
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}

    /// The job, never closed by hand: Windows closes it when this process
    /// ends, which ends everything in it.
    fn job() -> Option<&'static Job> {
        static JOB: OnceLock<Option<Job>> = OnceLock::new();
        JOB.get_or_init(|| unsafe {
            let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if job.is_null() {
                return None;
            }
            let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let set = SetInformationJobObject(
                job,
                JobObjectExtendedLimitInformation,
                (&raw const limits).cast(),
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            (set != 0).then_some(Job(job))
        })
        .as_ref()
    }

    pub fn adopt(child: &std::process::Child) {
        if let Some(job) = job() {
            unsafe {
                AssignProcessToJobObject(job.0, child.as_raw_handle() as HANDLE);
            }
        }
    }
}
