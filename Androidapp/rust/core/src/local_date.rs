//! Java LocalDate ISO canonical storage dates, full +/-999999999 year range.
//! Gregorian arithmetic, no chrono range narrowing; no locale/current clock.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub struct LocalDate {pub year:i32,pub month:u32,pub day:u32}
impl LocalDate {
 pub fn parse(s:&str)->Option<Self>{let d=Self::parse_java(s)?;if d.canonical()==s{Some(d)}else{None}}
 pub fn parse_java(s:&str)->Option<Self>{
  let (sign,rest)=if let Some(r)=s.strip_prefix('-'){(-1,r)}else if let Some(r)=s.strip_prefix('+'){(1,r)}else{(0,s)};
  let mut parts=rest.split('-');let ys=parts.next()?;let ms=parts.next()?;let ds=parts.next()?;
  if parts.next().is_some()||!ys.bytes().all(|b|b.is_ascii_digit())||ys.is_empty()||ms.len()!=2||ds.len()!=2||!ms.bytes().chain(ds.bytes()).all(|b|b.is_ascii_digit()){return None}
  if (sign==0 && ys.len()!=4)||(sign<0 && !(4..=10).contains(&ys.len()))||(sign>0 && !(5..=10).contains(&ys.len())){return None}
  let abs:i32=ys.parse().ok()?;if sign<0 && abs==0{return None}let year=if sign<0{-abs}else{abs};
  if !(-999999999..=999999999).contains(&year){return None}
  let date=Self{year,month:ms.parse().ok()?,day:ds.parse().ok()?};
  if date.month<1||date.month>12{return None}let leap=year%4==0&&(year%100!=0||year%400==0);let max=match date.month{2=>if leap{29}else{28},4|6|9|11=>30,_=>31};
  if date.day<1||date.day>max{return None}Some(date)
 }
 pub fn canonical(self)->String{let y=if self.year<0{format!("-{:04}",-self.year)}else if self.year>9999{format!("+{}",self.year)}else{format!("{:04}",self.year)};format!("{y}-{:02}-{:02}",self.month,self.day)}
 /// Absolute Gregorian day number. Euclidean eras also cover negative years.
 pub fn epoch_day(self)->i64{let y=self.year as i64-if self.month<=2{1}else{0};let era=y.div_euclid(400);let yoe=y-era*400;let m=self.month as i64;let shifted=m+if m>2{-3}else{9};let doy=(153*shifted+2)/5+self.day as i64-1;era*146097+yoe*365+yoe/4-yoe/100+doy-719468}
 pub fn anchor_moves_after(self,prior:Self)->Result<bool,()>{
  // Kotlin computes currentStart.plusDays(15) first: overflow is an error,
  // even when no candidate date could be later. Never silently set anchor.
  let limit=Self{year:999999999,month:12,day:31}.epoch_day();
  let threshold=prior.epoch_day().checked_add(15).ok_or(())?;if threshold>limit{return Err(())}Ok(self.epoch_day()>threshold)
 }
}
