//! Small in-place radix-2 FFT for the Vibe DJ analysis (power-of-two sizes only). The analysis
//! runs a few hundred thousand of these per track at most, so no SIMD crate is pulled in.

pub(crate) struct Fft {
    n: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
    rev: Vec<usize>,
    window: Vec<f32>,
    re: Vec<f32>,
    im: Vec<f32>,
}

impl Fft {
    /// An FFT of `n` points (a power of two) with a Hann window.
    pub(crate) fn new(n: usize) -> Self {
        assert!(n.is_power_of_two() && n >= 4);
        let bits = n.trailing_zeros();
        let rev = (0..n).map(|i| i.reverse_bits() >> (usize::BITS - bits)).collect();
        let tau = std::f32::consts::TAU;
        let cos = (0..n / 2).map(|k| (tau * k as f32 / n as f32).cos()).collect();
        let sin = (0..n / 2).map(|k| -(tau * k as f32 / n as f32).sin()).collect();
        let window = (0..n).map(|i| 0.5 - 0.5 * (tau * i as f32 / n as f32).cos()).collect();
        Self { n, cos, sin, rev, window, re: vec![0.0; n], im: vec![0.0; n] }
    }

    /// Windowed magnitude spectrum of `input` (`n` samples) into `out` (`n / 2 + 1` bins).
    pub(crate) fn magnitudes(&mut self, input: &[f32], out: &mut [f32]) {
        let n = self.n;
        for i in 0..n {
            self.re[self.rev[i]] = input[i] * self.window[i];
            self.im[self.rev[i]] = 0.0;
        }
        let mut size = 2;
        while size <= n {
            let half = size / 2;
            let step = n / size;
            for start in (0..n).step_by(size) {
                for k in 0..half {
                    let (c, s) = (self.cos[k * step], self.sin[k * step]);
                    let (a, b) = (start + k, start + k + half);
                    let tr = self.re[b] * c - self.im[b] * s;
                    let ti = self.re[b] * s + self.im[b] * c;
                    self.re[b] = self.re[a] - tr;
                    self.im[b] = self.im[a] - ti;
                    self.re[a] += tr;
                    self.im[a] += ti;
                }
            }
            size *= 2;
        }
        for (k, o) in out.iter_mut().enumerate().take(n / 2 + 1) {
            *o = (self.re[k] * self.re[k] + self.im[k] * self.im[k]).sqrt();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sine_peaks_at_its_bin() {
        let n = 512;
        let mut fft = Fft::new(n);
        let input: Vec<f32> = (0..n).map(|i| (std::f32::consts::TAU * 32.0 * i as f32 / n as f32).sin()).collect();
        let mut out = vec![0.0; n / 2 + 1];
        fft.magnitudes(&input, &mut out);
        let peak = out.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).unwrap().0;
        assert_eq!(peak, 32);
    }
}
