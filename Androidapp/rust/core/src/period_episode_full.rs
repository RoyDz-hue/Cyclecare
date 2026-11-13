//! Period chronology and episode-estimate decision. Epoch days are exact JVM primitives.
pub struct Decision {pub valid:bool,pub error:Option<String>,pub warning:Option<String>,pub duration:i32,pub typical:i32,pub confirm:bool,pub keep_end:bool}
pub fn decide(start:Option<i64>,end:Option<i64>,today:i64,ongoing:bool,typical:i32)->Decision {
 let fail=|message:&str|Decision{valid:false,error:Some(message.into()),warning:None,duration:0,typical,confirm:false,keep_end:false};
 let start=match start{Some(v)=>v,None=>return fail("Period start date is required")};
 let end=match end{Some(v)=>v,None=>return fail("Period end date is required")};
 if end<start{return fail("Period end date cannot be earlier than start date")}
 if start>today||end>today{return fail("Period dates cannot be in the future")}
 // Java LocalDate span fits i64. Kotlin narrows inclusive duration BEFORE >7.
 let duration=(end-start+1) as i32;let confirm=duration>7;
 let warning=if confirm {Some(if ongoing{format!("Your period has lasted {duration} days so far. That's longer than usual. Please check the start date. If it's right, it's worth speaking with a healthcare professional soon.")}else{format!("Your period lasted {duration} days. That's longer than usual. Please check the dates. If they're right, it's worth mentioning to a healthcare professional.")})}else{None};
 Decision{valid:true,error:None,warning,duration,typical:if confirm{typical}else{duration},confirm,keep_end:true}
}
