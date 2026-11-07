package main
import("context";"crypto/tls";"crypto/x509";"fmt";"net/http";"os";"time";madmin "github.com/minio/madmin-go/v3";minio "github.com/minio/minio-go/v7";"github.com/minio/minio-go/v7/pkg/credentials")
func main(){
 ctx,cancel:=context.WithTimeout(context.Background(),60*time.Second);defer cancel();pool:=x509.NewCertPool();ca,_:=os.ReadFile("/trust/ca.crt");if !pool.AppendCertsFromPEM(ca){panic("trust unavailable")};transport:=&http.Transport{TLSClientConfig:&tls.Config{RootCAs:pool,MinVersion:tls.VersionTLS12}};endpoint:="brain-minio.brain-data.svc:9000";root:=os.Getenv("ROOT_USER");secret:=os.Getenv("ROOT_PASSWORD");
 adm,e:=madmin.New(endpoint,root,secret,true);check("admin",e);adm.SetCustomTransport(transport);s3,e:=minio.New(endpoint,&minio.Options{Creds:credentials.NewStaticV4(root,secret,""),Secure:true,Transport:transport,Region:"us-east-1"});check("client",e)
 exists,e:=s3.BucketExists(ctx,"brain-media");check("bucket lookup",e);if !exists{check("create bucket",s3.MakeBucket(ctx,"brain-media",minio.MakeBucketOptions{Region:"us-east-1"}))};check("versioning",s3.SetBucketVersioning(ctx,"brain-media",minio.BucketVersioningConfiguration{Status:"Enabled"}));
 policy:=[]byte(`{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["s3:GetBucketLocation","s3:GetBucketVersioning"],"Resource":["arn:aws:s3:::brain-media"]},{"Effect":"Allow","Action":["s3:PutObject","s3:GetObject","s3:GetObjectVersion"],"Resource":["arn:aws:s3:::brain-media/uploads/*"]}]}`)
 check("policy",adm.AddCannedPolicy(ctx,"brain-media-service",policy));check("user",adm.AddUser(ctx,os.Getenv("MEDIA_ACCESS_KEY"),os.Getenv("MEDIA_SECRET_KEY")));check("attach policy",adm.SetPolicy(ctx,"brain-media-service",os.Getenv("MEDIA_ACCESS_KEY"),false));fmt.Println("PASS private versioned bucket and scoped Media user provisioned; no credentials printed")
}
func check(stage string,e error){if e!=nil{fmt.Println("FAIL "+stage);os.Exit(1)}}
