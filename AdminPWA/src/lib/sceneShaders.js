// Generated from the reviewed home-scene prototype. Fragment shaders for the
// three member-plan bodies (glass gem, mirror sphere, frosted octahedron) and the
// calm background field. Pure strings: no data and no network.
export const BODY_FRAGMENT = (steps) => `precision highp float;uniform vec2 R;uniform float T;uniform vec4 B[3];uniform vec3 C[3];uniform vec3 RT[3];uniform float Q;
mat3 rot(vec3 a){float cx=cos(a.x),sx=sin(a.x),cy=cos(a.y),sy=sin(a.y),cz=cos(a.z),sz=sin(a.z);
 mat3 X=mat3(1.,0.,0.,0.,cx,sx,0.,-sx,cx),Y=mat3(cy,0.,-sy,0.,1.,0.,sy,0.,cy),Z=mat3(cz,sz,0.,-sz,cz,0.,0.,0.,1.);return Z*Y*X;}
float sdHex(vec3 p){vec3 q=abs(p);float d1=q.z-.46;float d2=max(q.x*.866025+q.y*.5,q.y)-.86;return length(max(vec2(d1,d2),0.))+min(max(d1,d2),0.)-.09;}
float sdOct(vec3 p){vec3 q=abs(p);return (q.x+q.y+q.z-.95)*.57735-.05;}
float map(vec3 p,int k){if(k==0)return sdHex(p);if(k==1)return length(p)-.92;return sdOct(p);}
vec3 nrm(vec3 p,int k){vec2 e=vec2(.004,0.);return normalize(vec3(map(p+e.xyy,k)-map(p-e.xyy,k),map(p+e.yxy,k)-map(p-e.yxy,k),map(p+e.yyx,k)-map(p-e.yyx,k)));}
vec3 env(vec3 d){float y=d.y;vec3 c=mix(vec3(.12,.2,.17),vec3(.85,.92,.86),smoothstep(-.3,1.,y));
 c+=vec3(1.,.97,.9)*smoothstep(.45,.15,abs(d.x+.35))*smoothstep(.5,.2,abs(y-.6))*1.6;
 c+=vec3(1.,.55,.4)*smoothstep(.1,.04,abs(d.x-.75))*smoothstep(.8,.2,abs(y))*1.2;
 c+=vec3(.83,1.,.35)*smoothstep(.5,-.6,y)*.12;
 c=mix(c,vec3(.05,.09,.08),smoothstep(0.,-.6,y)*.6);return c;}
void main(){vec2 f=gl_FragCoord.xy;f.y=R.y-f.y;float S=min(R.x,R.y*1.25);vec2 uv=(f-.5*R)/S;
 vec3 col=vec3(0.);float al=0.;
 for(int i=0;i<3;i++){vec2 c=(B[i].xy-.5*R)/S;float rr=B[i].z/S;if(rr<.001)continue;vec2 p2=(uv-c)/rr;
  // soft contact shadow under the body
  float sh=smoothstep(1.35,.2,length((p2-vec2(.25,.95))*vec2(.8,1.7)));al=max(al,.55*sh);
  if(length(p2)>1.45)continue;
  mat3 M=rot(RT[i]);vec3 ro=vec3(p2,3.),rd=vec3(0.,0.,-1.);float t=0.,mh=9.;bool hit=false;
  for(int s=0;s<Q_STEPS;s++){vec3 pp=ro+rd*t;float h=map(M*pp,i);mh=min(mh,h);if(h<.004){hit=true;break;}t+=h;if(t>6.)break;}
  if(hit){vec3 pw=ro+rd*t;vec3 n=M*vec3(0.);vec3 pl=M*pw;vec3 nl=nrm(pl,i);vec3 N=nl*M;
   float cs=clamp(dot(-rd,N),0.,1.);float F=.04+.96*pow(1.-cs,5.);vec3 rf=reflect(rd,N);vec3 refl=env(rf);
   vec3 tint=C[i];vec3 res;
   if(i==1){ // polished mirror sphere, tinted metal
    res=refl*mix(vec3(1.),tint,.6)*1.1+tint*.22*(.4+.6*N.y);res+=pow(max(dot(rf,normalize(vec3(-.4,.7,.6))),0.),120.)*vec3(.9);
   }else{ // glass: refraction with thickness absorption
    vec3 rr2=refract(rd,N,1./1.45);vec3 q=pl;vec3 dl=M*rr2;float tt=.02;
    for(int s=0;s<8;s++){float h=-map(q+dl*tt,i);tt+=max(h,.04);if(h<.02)break;}
    vec3 absn=exp(-tt*(vec3(1.)-tint)*(i==2?.9:(i==0?.75:1.1)));vec3 exitd=normalize(rr2+N*.35);
    vec3 inner=env(exitd)*absn*(i==0?.85:1.5)+tint*(i==0?.9:(.45+.3*N.y))*absn;
    res=mix(inner,refl*1.15,F+(i==0?.1:.15));res+=pow(max(dot(rf,normalize(vec3(-.4,.7,.6))),0.),90.)*vec3(1.4);
    if(i==2)res=mix(res,tint*.5+env(rf)*.5,.18);}
   float rim=pow(1.-cs,3.);res+=tint*rim*.18;
   col=res;al=1.;}else{float gw=smoothstep(.16,0.,mh)*.1;col+=C[i]*gw;al=max(al,gw);}
 }
 gl_FragColor=vec4(pow(col,vec3(.92)),al);}`.replace(/Q_STEPS/g, String(steps));
export const BG_FRAGMENT = `precision highp float;uniform vec2 R;uniform float T;uniform vec4 B[3];uniform vec3 C[3];uniform vec3 RT[3];uniform float Q;
mat3 rot(vec3 a){float cx=cos(a.x),sx=sin(a.x),cy=cos(a.y),sy=sin(a.y),cz=cos(a.z),sz=sin(a.z);
 mat3 X=mat3(1.,0.,0.,0.,cx,sx,0.,-sx,cx),Y=mat3(cy,0.,-sy,0.,1.,0.,sy,0.,cy),Z=mat3(cz,sz,0.,-sz,cz,0.,0.,0.,1.);return Z*Y*X;}
float sdHex(vec3 p){vec3 q=abs(p);float d1=q.z-.46;float d2=max(q.x*.866025+q.y*.5,q.y)-.86;return length(max(vec2(d1,d2),0.))+min(max(d1,d2),0.)-.09;}
float sdOct(vec3 p){vec3 q=abs(p);return (q.x+q.y+q.z-.95)*.57735-.05;}
float map(vec3 p,int k){if(k==0)return sdHex(p);if(k==1)return length(p)-.92;return sdOct(p);}
vec3 nrm(vec3 p,int k){vec2 e=vec2(.004,0.);return normalize(vec3(map(p+e.xyy,k)-map(p-e.xyy,k),map(p+e.yxy,k)-map(p-e.yxy,k),map(p+e.yyx,k)-map(p-e.yyx,k)));}
vec3 env(vec3 d){float y=d.y;vec3 c=mix(vec3(.12,.2,.17),vec3(.85,.92,.86),smoothstep(-.3,1.,y));
 c+=vec3(1.,.97,.9)*smoothstep(.45,.15,abs(d.x+.35))*smoothstep(.5,.2,abs(y-.6))*1.6;
 c+=vec3(1.,.55,.4)*smoothstep(.1,.04,abs(d.x-.75))*smoothstep(.8,.2,abs(y))*1.2;
 c+=vec3(.83,1.,.35)*smoothstep(.5,-.6,y)*.12;
 c=mix(c,vec3(.05,.09,.08),smoothstep(0.,-.6,y)*.6);return c;}
void main(){vec2 f=gl_FragCoord.xy;f.y=R.y-f.y;float S=min(R.x,R.y*1.25);vec2 uv=(f-.5*R)/S;
 vec3 col=vec3(.039,.075,.067);
 float d=length(uv);
 float wave=sin(uv.x*14.+T*.25)*.035+sin(uv.x*5.-T*.15)*.05;col+=vec3(1,.42,.29)*smoothstep(.006,0.,abs(uv.y-wave))*.55;
 float wave2=cos(uv.x*9.+T*.2)*.06;col+=vec3(.83,1,.35)*smoothstep(.005,0.,abs(uv.y+.14-wave2))*.28;
 
 vec2 g=abs(fract(uv*18.)-.5);col+=vec3(.95,.93,.88)*smoothstep(.47,.5,max(g.x,g.y))*.03;
 gl_FragColor=vec4(pow(col,vec3(.92)),1.);}`;
