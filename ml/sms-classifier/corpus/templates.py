"""Message templates for the OpenCaller SMS corpus.

Every template imitates the *shape* of a common SMS format in Bangladesh, India or the United States: where the code
sits, the keywords around it, how amounts and card digits are written, the sender IDs. The values filled in are made
up (codes, amounts, card digits, order numbers, names, links), and most brands are invented, so no message belongs to
a real person. The banks named in transaction templates are real, but their formats here are approximations written
for this project, not copies of real messages; check them against real messages before relying on them
(docs/phase.md, Open issues).

Placeholders: {code} one-time code, {amt} amount, {bal} balance, {last4} card or account digits, {merchant},
{date}, {time}, {order}, {awb}, {phone} a fake number, {domain} a domain, {hash} an 11-character SMS Retriever hash,
{min} minutes, {name} a first name, {url} a link, {pct} a percentage.
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Template:
    id: str
    country: str
    lang: str
    category: str
    sender: str
    text: str
    otp: bool = False
    digits: int = 6
    origin: bool = False
    app_hash: bool = False
    expiry: bool = False
    txn: tuple = ()  # (currency, has_merchant) for a transaction
    alpha_code: bool = False
    extra: dict = field(default_factory=dict)


T = Template

# One-time codes. "otp=True" means the message carries a code the extractor must find.
OTP = [
    # United States
    T("us-otp-01", "US", "en", "OTP", "227898", "Your Shopnest verification code is {code}. Don't share it with anyone.", otp=True),
    T("us-otp-02", "US", "en", "OTP", "48291", "{code} is your Ridely code. It expires in {min} minutes.", otp=True, expiry=True),
    T("us-otp-03", "US", "en", "OTP", "33445", "Use {code} to sign in to Quillmark. Never share this code.", otp=True),
    T("us-otp-04", "US", "en", "OTP", "+15550100", "Northpeak Bank: Your one-time passcode is {code}. We will never call you to ask for it.", otp=True, digits=8),
    T("us-otp-05", "US", "en", "OTP", "71234", "Your login code: {code}\n\n@login.quillmark.example #{code}", otp=True, origin=True),
    T("us-otp-06", "US", "en", "OTP", "90210", "<#> Your Brightpay code is {code}\n{hash}", otp=True, app_hash=True),
    T("us-otp-07", "US", "en", "OTP", "55667", "G-{code} is your verification code.", otp=True),
    T("us-otp-08", "US", "en", "OTP", "86753", "Ridely: {code} is your code to confirm this device. Valid for {min} min.", otp=True, expiry=True, digits=4),
    T("us-otp-09", "US", "en", "OTP", "24680", "Your security code is {code}. If you didn't request it, ignore this message.", otp=True),
    T("us-otp-10", "US", "en", "OTP", "13579", "Enter {code} on the Coinvale app to finish setting up 2-step verification.", otp=True, digits=7),
    T("us-otp-11", "US", "en", "OTP", "11223", "Halcyon Health: Your access code is {code}. It is valid for {min} minutes.", otp=True, expiry=True),
    T("us-otp-12", "US", "en", "OTP", "99887", "Shopnest: code {code}. Reply STOP to opt out.", otp=True, digits=5),
    T("us-otp-13", "US", "en", "OTP", "44556", "Your Tallyfox passcode: {code}\n\n@tallyfox.example #{code} @pay.tallyfox.example", otp=True, origin=True),
    T("us-otp-14", "US", "en", "OTP", "66778", "Northpeak Bank alert: someone is signing in with code {code}. If this wasn't you, call us now.", otp=True),
    T("us-otp-15", "US", "en", "OTP", "31415", "{code} - use this code to verify your Glimmer account. Code expires in {min} min.", otp=True, expiry=True, digits=4),
    # India
    T("in-otp-01", "IN", "en", "OTP", "VM-HDFCBK-S", "{code} is the OTP for your transaction of Rs.{amt} at {merchant} on HDFC Bank card ending {last4}. Valid for {min} mins. Do not share it with anyone.", otp=True, expiry=True),
    T("in-otp-02", "IN", "en", "OTP", "JD-SBIOTP-S", "Dear Customer, OTP for login is {code}. Valid for {min} minutes. Never share OTP with anyone. -SBI", otp=True, expiry=True),
    T("in-otp-03", "IN", "en", "OTP", "AX-ICICIT-S", "Your OTP is {code} for txn of INR {amt} at {merchant}. OTP valid for {min} mins. Do not share with anyone - ICICI Bank", otp=True, expiry=True),
    T("in-otp-04", "IN", "en", "OTP", "VK-PAYTMN-S", "{code} is your verification code for Kiranapay. Please do not share it.", otp=True),
    T("in-otp-05", "IN", "en", "OTP", "BP-SWIGGY-S", "Your OTP for Biryanibox is {code}. Please do not share it with anyone.\n{hash}", otp=True, app_hash=True, digits=4),
    T("in-otp-06", "IN", "en", "OTP", "AD-AXISBK-S", "Your OTP for net banking login is {code}. It is valid for {min} minutes. Axis Bank never asks for OTP.", otp=True, expiry=True),
    T("in-otp-07", "IN", "en", "OTP", "TM-UIDAIG-G", "{code} is your OTP for Aadhaar verification. Valid for {min} mins. -UIDAI", otp=True, expiry=True),
    T("in-otp-08", "IN", "hi", "OTP", "VM-KOTAKB-S", "आपका ओटीपी {code} है। यह {min} मिनट के लिए मान्य है। इसे किसी के साथ साझा न करें।", otp=True, expiry=True),
    T("in-otp-09", "IN", "hi", "OTP", "JM-IRCTCI-S", "{code} आपका सत्यापन कोड है। कृपया इसे किसी को न बताएं।", otp=True),
    T("in-otp-10", "IN", "en", "OTP", "VM-ZOMATO-S", "Use OTP {code} to log in to Thalibox. This OTP is valid for {min} minutes.", otp=True, expiry=True, digits=4),
    T("in-otp-11", "IN", "en", "OTP", "AD-FLPKRT-S", "Your Cartwala verification code is {code}.\n\n@cartwala.example #{code}", otp=True, origin=True),
    T("in-otp-12", "IN", "en", "OTP", "VD-OLAOLA-S", "{code} is your login OTP for Raftaar Rides. Do not share it with anyone.", otp=True, digits=4),
    T("in-otp-13", "IN", "hi", "OTP", "AX-HDFCBK-S", "ओटीपी: {code}. आपके कार्ड {last4} पर लेनदेन के लिए। {min} मिनट तक मान्य।", otp=True, expiry=True),
    T("in-otp-14", "IN", "en", "OTP", "TX-PHONPE-S", "<#> {code} is your Payvani verification code. Code valid for {min} minutes.\n{hash}", otp=True, app_hash=True, expiry=True),
    T("in-otp-15", "IN", "en", "OTP", "VM-IPAYTM-S", "OTP to add beneficiary is {code}. If not you, call 1800-555-0100 immediately.", otp=True),
    # Bangladesh
    T("bd-otp-01", "BD", "en", "OTP", "bKash", "Your bKash verification code is {code}. Do not share this code with anyone. bKash never asks for your code.", otp=True),
    T("bd-otp-02", "BD", "en", "OTP", "NAGAD", "Your Nagad OTP is {code}. It will expire in {min} minutes. Never share your OTP and PIN.", otp=True, expiry=True),
    T("bd-otp-03", "BD", "bn", "OTP", "bKash", "আপনার বিকাশ ভেরিফিকেশন কোড {code}। কোডটি কারো সাথে শেয়ার করবেন না।", otp=True),
    T("bd-otp-04", "BD", "bn", "OTP", "NAGAD", "আপনার ওটিপি কোড {code}। এটি {min} মিনিটের জন্য বৈধ।", otp=True, expiry=True),
    T("bd-otp-05", "BD", "en", "OTP", "DBBL", "Your DBBL Internet Banking OTP is {code}. Valid for {min} minutes.", otp=True, expiry=True),
    T("bd-otp-06", "BD", "en", "OTP", "BRAC BANK", "OTP for your BRAC Bank card transaction of BDT {amt} is {code}. Do not disclose it to anyone.", otp=True),
    T("bd-otp-07", "BD", "en", "OTP", "16216", "<#> Your Bazaarly code is {code}\n{hash}", otp=True, app_hash=True, digits=4),
    T("bd-otp-08", "BD", "bn", "OTP", "Rokomari", "{code} আপনার যাচাইকরণ কোড। এটি {min} মিনিট পর্যন্ত বৈধ।", otp=True, expiry=True, digits=4),
    T("bd-otp-09", "BD", "en", "OTP", "CityBank", "Dear Customer, {code} is your OTP for Citytouch login. Please do not share it with anyone.", otp=True),
    T("bd-otp-10", "BD", "en", "OTP", "Pathao", "Your Chaka Rides login code is {code}.\n\n@chaka.example #{code}", otp=True, origin=True, digits=4),
    T("bd-otp-11", "BD", "bn", "OTP", "upay", "উপায় ওটিপি: {code}। কাউকে বলবেন না।", otp=True),
    T("bd-otp-12", "BD", "en", "OTP", "Robi", "Your Robi app verification code is {code}. Valid for {min} minutes.", otp=True, expiry=True, digits=4),
    T("bd-otp-13", "BD", "bn", "OTP", "GP", "{code} হল আপনার মাইজিপি ভেরিফিকেশন কোড। {min} মিনিটের মধ্যে ব্যবহার করুন।", otp=True, expiry=True),
    T("bd-otp-14", "BD", "en", "OTP", "EBL", "EBL Skybanking: your one time password is {code}. Do not share it.", otp=True, digits=8),
    T("bd-otp-15", "BD", "en", "OTP", "Daraz", "Use {code} as your Haatbazar login code. It expires in {min} minutes.", otp=True, expiry=True),
]

# Bank and wallet transactions. "txn" = (currency, merchant present).
TRANSACTION = [
    T("in-txn-01", "IN", "en", "TRANSACTION", "VM-HDFCBK-T", "Rs.{amt} spent on HDFC Bank Card x{last4} at {merchant} on {date}. Avl bal: Rs.{bal}. Not you? Call 1800-555-0101", txn=("INR", True)),
    T("in-txn-02", "IN", "en", "TRANSACTION", "JD-SBIINB-T", "Dear Customer, your A/c XX{last4} is debited by Rs.{amt} on {date} trf to {merchant}. -SBI", txn=("INR", True)),
    T("in-txn-03", "IN", "en", "TRANSACTION", "AX-ICICIT-T", "ICICI Bank Acct XX{last4} credited with INR {amt} on {date}. Info: UPI. Avl Bal INR {bal}.", txn=("INR", False)),
    T("in-txn-04", "IN", "en", "TRANSACTION", "AD-AXISBK-T", "INR {amt} debited from A/c no. XX{last4} on {date} at {merchant}. Avl Bal INR {bal} - Axis Bank", txn=("INR", True)),
    T("in-txn-05", "IN", "en", "TRANSACTION", "VM-KOTAKB-T", "Sent Rs.{amt} from Kotak Bank AC X{last4} to {merchant} on {date}. Not you? Call 1800-555-0102", txn=("INR", True)),
    T("in-txn-06", "IN", "hi", "TRANSACTION", "VM-HDFCBK-T", "आपके खाते XX{last4} से रु.{amt} डेबिट किए गए हैं। उपलब्ध शेष रु.{bal}।", txn=("INR", False)),
    T("bd-txn-01", "BD", "en", "TRANSACTION", "bKash", "Cash Out Tk {amt} to {phone} successful. Fee Tk 18.50. Balance Tk {bal}. TrxID 9JK{order} at {date}", txn=("BDT", False)),
    T("bd-txn-02", "BD", "en", "TRANSACTION", "NAGAD", "Payment of Tk {amt} to {merchant} is successful. Balance: Tk {bal}. TxnID: 7{order}", txn=("BDT", True)),
    T("bd-txn-03", "BD", "en", "TRANSACTION", "DBBL", "Your A/C ****{last4} has been debited by BDT {amt} on {date} for POS purchase at {merchant}.", txn=("BDT", True)),
    T("bd-txn-04", "BD", "en", "TRANSACTION", "BRAC BANK", "BDT {amt} has been credited to your account **{last4} on {date}. Available balance BDT {bal}.", txn=("BDT", False)),
    T("bd-txn-05", "BD", "en", "TRANSACTION", "CityBank", "Dear Cardmember, BDT {amt} was spent on your card ending {last4} at {merchant} on {date}.", txn=("BDT", True)),
    T("bd-txn-06", "BD", "bn", "TRANSACTION", "bKash", "আপনি {phone} নম্বরে ৳{amt} সেন্ড মানি করেছেন। ব্যালেন্স ৳{bal}।", txn=("BDT", False)),
    T("us-txn-01", "US", "en", "TRANSACTION", "24273", "Chase: A ${amt} transaction was made on your card ending in {last4} at {merchant}.", txn=("USD", True)),
    T("us-txn-02", "US", "en", "TRANSACTION", "73981", "BofA: Purchase of ${amt} at {merchant} on card ending {last4} was approved.", txn=("USD", True)),
    T("us-txn-03", "US", "en", "TRANSACTION", "93557", "Wells Fargo: A ${amt} debit was made on your account ending {last4} on {date}.", txn=("USD", False)),
    T("us-txn-04", "US", "en", "TRANSACTION", "692484", "Citi Alert: A ${amt} transaction was made at {merchant} on card ending in {last4}.", txn=("USD", True)),
    T("us-txn-05", "US", "en", "TRANSACTION", "227462", "Capital One: Your card ending in {last4} was used for ${amt} at {merchant}.", txn=("USD", True)),
    T("us-txn-06", "US", "en", "TRANSACTION", "24273", "Chase: You received ${amt} via a deposit to account ending in {last4}.", txn=("USD", False)),
]

BILL = [
    T("us-bill-01", "US", "en", "BILL", "35050", "Brightgrid Energy: your bill of ${amt} is due on {date}. Pay at {url}"),
    T("us-bill-02", "US", "en", "BILL", "28767", "Reminder: your Skylink Wireless payment of ${amt} is due {date}. Reply HELP for help."),
    T("us-bill-03", "US", "en", "BILL", "62684", "Your Clearwater Utilities statement is ready. Amount due: ${amt}, due date {date}."),
    T("in-bill-01", "IN", "en", "BILL", "AD-JIOINF-T", "Your bill of Rs.{amt} for mobile {phone} is due on {date}. Pay now to avoid late fee."),
    T("in-bill-02", "IN", "en", "BILL", "VM-TATAPW-T", "Electricity bill for CA no. {order} is Rs.{amt}, due date {date}. Pay via app."),
    T("in-bill-03", "IN", "hi", "BILL", "VK-BSNLMB-T", "आपका बिल रु.{amt} है, देय तिथि {date}। कृपया समय पर भुगतान करें।"),
    T("bd-bill-01", "BD", "en", "BILL", "DESCO", "Your electricity bill for account {order} is Tk {amt}. Last date of payment {date}."),
    T("bd-bill-02", "BD", "en", "BILL", "Titas Gas", "Dear customer, your gas bill of Tk {amt} is due on {date}. Please pay to avoid disconnection."),
    T("bd-bill-03", "BD", "bn", "BILL", "WASA", "আপনার পানির বিল ৳{amt}। পরিশোধের শেষ তারিখ {date}।"),
]

DELIVERY = [
    T("us-del-01", "US", "en", "DELIVERY", "28782", "Shopnest: your order {order} has shipped. Track it at {url}"),
    T("us-del-02", "US", "en", "DELIVERY", "468311", "SwiftParcel: package {awb} is out for delivery today."),
    T("us-del-03", "US", "en", "DELIVERY", "75611", "Your Ridely Eats order is on its way! Your courier {name} will arrive in {min} minutes."),
    T("us-del-04", "US", "en", "DELIVERY", "468311", "SwiftParcel: delivered. Your package {awb} was left at the front door at {time}."),
    T("in-del-01", "IN", "en", "DELIVERY", "AD-FLPKRT-T", "Your Cartwala order {order} has been shipped and will be delivered by {date}."),
    T("in-del-02", "IN", "en", "DELIVERY", "VM-DLHVRY-T", "Shipment AWB {awb} is out for delivery. Delivery agent {name}, {phone}."),
    T("in-del-03", "IN", "hi", "DELIVERY", "BP-ECOMEX-T", "आपका ऑर्डर {order} भेज दिया गया है और {date} तक पहुंच जाएगा।"),
    T("bd-del-01", "BD", "en", "DELIVERY", "Pathao", "Your parcel {order} has been picked up and will be delivered within 48 hours."),
    T("bd-del-02", "BD", "en", "DELIVERY", "Steadfast", "Dear customer, your parcel {awb} is out for delivery. Rider: {name} {phone}"),
    T("bd-del-03", "BD", "bn", "DELIVERY", "RedX", "আপনার পার্সেল {awb} ডেলিভারির জন্য বের হয়েছে। রাইডার {name}।"),
    T("bd-del-04", "BD", "en", "DELIVERY", "Daraz", "Your Haatbazar package {order} has been delivered. Thank you for shopping with us."),
]

PROMOTION = [
    T("us-pro-01", "US", "en", "PROMOTION", "88123", "Shopnest: {pct}% off everything this weekend only! Use code SAVE{pct} at checkout. Reply STOP to opt out."),
    T("us-pro-02", "US", "en", "PROMOTION", "55123", "Ridely: get {pct}% off your next 3 rides. Offer ends {date}. Txt STOP to end."),
    T("us-pro-03", "US", "en", "PROMOTION", "71717", "Flash sale at Glimmer! Buy 1 get 1 free on all items. Shop now {url}"),
    T("in-pro-01", "IN", "en", "PROMOTION", "AD-CARTWL-P", "Big Billion Sale is LIVE! Flat {pct}% off on mobiles. Shop now {url} T&C"),
    T("in-pro-02", "IN", "en", "PROMOTION", "VM-BIRYNI-P", "Craving biryani? Get {pct}% OFF up to Rs.120 on your next order. Use code TASTY{pct}"),
    T("in-pro-03", "IN", "hi", "PROMOTION", "JD-JIOMKT-P", "सिर्फ आज! सभी प्लान पर {pct}% कैशबैक। अभी रिचार्ज करें।"),
    T("in-pro-04", "IN", "en", "PROMOTION", "BZ-FASHNX-P", "End of season sale: up to {pct}% off on top brands. Hurry, offer valid till {date}."),
    T("bd-pro-01", "BD", "en", "PROMOTION", "Haatbazar", "11.11 Mega Sale! Up to {pct}% off on electronics. Download the app now."),
    T("bd-pro-02", "BD", "bn", "PROMOTION", "GP", "মাত্র ৳{amt} টাকায় ২০ জিবি ইন্টারনেট! ডায়াল *121*3# এখনই।"),
    T("bd-pro-03", "BD", "en", "PROMOTION", "Robi", "Get 30GB for Tk {amt}, valid 30 days. Dial *123*777# to buy now."),
    T("bd-pro-04", "BD", "bn", "PROMOTION", "Bazaarly", "ঈদ অফার! সব পণ্যে {pct}% পর্যন্ত ছাড়। আজই অর্ডার করুন।"),
]

SPAM = [
    T("us-spm-01", "US", "en", "SPAM", "+15550123", "Congratulations! You have been selected to receive a $1000 gift card. Claim now: {url}"),
    T("us-spm-02", "US", "en", "SPAM", "+15550134", "You are pre-approved for a $5,000 loan with no credit check. Reply YES to get your money today."),
    T("us-spm-03", "US", "en", "SPAM", "+15550145", "Hi {name}, lose 20 lbs in 2 weeks with this one trick! Limited stock {url}"),
    T("in-spm-01", "IN", "en", "SPAM", "+919555012345", "Dear user you have WON Rs.25,00,000 in KBC lucky draw. Call {phone} to claim your prize."),
    T("in-spm-02", "IN", "en", "SPAM", "+919555012346", "Instant personal loan up to Rs.5 lakh approved! No documents. Click {url}"),
    T("in-spm-03", "IN", "hi", "SPAM", "+919555012347", "बधाई हो! आपने 10 लाख रुपये की लॉटरी जीती है। इनाम पाने के लिए {phone} पर कॉल करें।"),
    T("in-spm-04", "IN", "en", "SPAM", "+919555012348", "Earn Rs.5000 daily from home! Part-time job, no experience needed. WhatsApp {phone}"),
    T("bd-spm-01", "BD", "en", "SPAM", "+8801555012345", "Congratulations! You won 5 lakh taka in our mobile lottery. Call {phone} now to receive it."),
    T("bd-spm-02", "BD", "bn", "SPAM", "+8801555012346", "অভিনন্দন! আপনি ১০ লক্ষ টাকা জিতেছেন। পুরস্কার পেতে {phone} নম্বরে কল করুন।"),
    T("bd-spm-03", "BD", "en", "SPAM", "+8801555012347", "Earn 2000 taka daily by working online from home. Join now {url}"),
]

PHISHING = [
    T("us-phi-01", "US", "en", "PHISHING", "+15550156", "Northpeak Bank: your account has been locked due to suspicious activity. Verify now at {phish}"),
    T("us-phi-02", "US", "en", "PHISHING", "+15550167", "SwiftParcel: we couldn't deliver your package. Pay the $1.99 redelivery fee at {phish}"),
    T("us-phi-03", "US", "en", "PHISHING", "+15550178", "IRS notice: you have an unclaimed tax refund. Submit your details at {phish} within 24 hours."),
    T("in-phi-01", "IN", "en", "PHISHING", "+919555012349", "Dear customer your SBI account will be blocked today. Update your PAN/KYC at {phish}"),
    T("in-phi-02", "IN", "en", "PHISHING", "+919555012350", "Your electricity will be disconnected tonight at 9:30 pm. Call officer {phone} or update at {phish}"),
    T("in-phi-03", "IN", "hi", "PHISHING", "+919555012351", "प्रिय ग्राहक, आपका खाता बंद हो जाएगा। तुरंत KYC अपडेट करें {phish}"),
    T("bd-phi-01", "BD", "en", "PHISHING", "+8801555012348", "Your bKash account has been suspended. Verify your PIN at {phish} to reactivate."),
    T("bd-phi-02", "BD", "bn", "PHISHING", "+8801555012349", "আপনার নগদ অ্যাকাউন্ট বন্ধ হয়ে যাবে। এখনই যাচাই করুন {phish}"),
    T("bd-phi-03", "BD", "en", "PHISHING", "+8801555012350", "Dear customer, your DBBL card is blocked. Click {phish} and enter your card number and PIN."),
]

# Messages from people, some with numbers that are NOT codes (times, amounts, flat numbers, phone numbers).
PERSONAL = [
    T("us-per-01", "US", "en", "PERSONAL", "+15550189", "Hey {name}, running 10 mins late. Save me a seat?"),
    T("us-per-02", "US", "en", "PERSONAL", "+15550190", "Can you pick up milk on the way home? Also the code for the gate changed, ask me when you're here."),
    T("us-per-03", "US", "en", "PERSONAL", "+15550191", "Dinner at 7:30 tonight? I booked a table for 4."),
    T("us-per-04", "US", "en", "PERSONAL", "+15550192", "I sent you ${amt} for the tickets. Room is 1204, see you there."),
    T("us-per-05", "US", "en", "PERSONAL", "+15550193", "Happy birthday {name}!! Call me when you're free. My new number is {phone}"),
    T("in-per-01", "IN", "en", "PERSONAL", "+919555012352", "Bhai reached home safely. Flat no 4021, building C. Come by 8."),
    T("in-per-02", "IN", "hi", "PERSONAL", "+919555012353", "माँ, मैं शाम 6 बजे तक घर पहुँच जाऊँगा। खाना मत बनाना।"),
    T("in-per-03", "IN", "en", "PERSONAL", "+919555012354", "Meeting moved to 3pm. Call me on {phone} if you can't make it."),
    T("in-per-04", "IN", "en", "PERSONAL", "+919555012355", "Paid Rs {amt} for the cab, will split later. Train is 12951 from platform 3."),
    T("bd-per-01", "BD", "bn", "PERSONAL", "+8801555012351", "আমি রাস্তায় আছি, ২০ মিনিটের মধ্যে আসছি।"),
    T("bd-per-02", "BD", "en", "PERSONAL", "+8801555012352", "Assalamu alaikum, the class is at 10 tomorrow, room 305. Don't be late!"),
    T("bd-per-03", "BD", "bn", "PERSONAL", "+8801555012353", "ভাই, বাসার ঠিকানা বাড়ি ১২, রোড ৭। সন্ধ্যায় দেখা হবে।"),
    T("bd-per-04", "BD", "en", "PERSONAL", "+8801555012354", "Sent you {amt} taka for the books. Call me at {phone} after Maghrib."),
]

ALL = OTP + TRANSACTION + BILL + DELIVERY + PROMOTION + SPAM + PHISHING + PERSONAL
