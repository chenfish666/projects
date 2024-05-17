import smtplib
import mysql.connector
import datetime

from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from email.mime.base import MIMEBase
from email import encoders

from apscheduler.schedulers.background import BackgroundScheduler
from datetime import datetime
import datetime
import os

def send_email(sender_email, sender_password, receiver_email):

    ppt_file = f"{name[n]}'s birthday_wish.pptx"
    # 設置郵件內容
    msg = MIMEMultipart()
    msg['From'] = sender_email
    msg['To'] = receiver_email
    msg['Subject'] = 'Birthday Greetings'
    
    body = f'Happy Birthday, {name[n]}!'
    msg.attach(MIMEText(body, 'pdf'))
    
    with open(ppt_file, 'rb') as attachment: 
        ppt_part = MIMEBase('application', 'octet-stream')
        ppt_part.set_payload(attachment.read())
    
    encoders.encode_base64(ppt_part)
    ppt_part.add_header('Content-Disposition', f"attachment; filename= {name[n]}.pptx")
    msg.attach(ppt_part)

    server = smtplib.SMTP('smtp.gmail.com', 587)
    server.starttls()
    server.login(sender_email, sender_password)
    server.sendmail(sender_email, receiver_email, msg.as_string())
    server.quit()
    
sender_email = '' #寄送帳號
sender_password = '' #設置密碼
receiver_email = '' #收件帳號

mydb = mysql.connector.connect(
  host="localhost",
  user="root",
  password="",
  database="project"
)

mycurser = mydb.cursor()

sql = "select * from udata"
mycurser.execute(sql)

udata = mycurser.fetchall()

name = [i[1] for i in udata]
position = [i[2] for i in udata]
birthdate = [i[3] for i in udata]
graduation_year = [i[4] for i in udata]
gmail = [i[5] for i in udata]

birthday_people = []
today = datetime.datetime.now().strftime("%Y-%m-%d")
for j in birthdate:
    n = birthdate.index(j)
    if today[6:10] == str(j)[6:10]:
        print('cool') #印出一些字表示成功寄出賀卡
        birthday_people.append(name[n])

if len(birthday_people) > 0:
    send_email(sender_email,sender_password,receiver_email)
    birthday_people = []