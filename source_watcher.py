#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import sys
import json
import re
import asyncio
from telethon import TelegramClient
from telethon.sessions import StringSession
from telethon.errors import SessionPasswordNeededError

def extract_price(text):
    if not text:
        return None
    # Match patterns like 3.12$, $3.12, 0,13$, 3.12 USD
    matches = re.findall(r'(?:\$\s*(\d+(?:[.,]\d+)?)|(\d+(?:[.,]\d+)?)\s*(?:\$|USD|usd))', text)
    if matches:
        for m in matches[-1]:
            if m:
                return float(m.replace(',', '.'))
    return None

def is_button_unavailable(btn):
    text = (getattr(btn, 'text', '') or '').lower()
    if '🔴' in text or '❌' in text or 'نفد' in text or 'غير متوفر' in text or 'out of stock' in text:
        return True
    raw_btn = getattr(btn, 'button', None)
    if raw_btn:
        style = getattr(raw_btn, 'style', None)
        if style and 'danger' in str(style).lower():
            return True
    return False

def get_button_emoji_id(btn):
    raw_btn = getattr(btn, 'button', None)
    if raw_btn:
        icon_id = getattr(raw_btn, 'icon_custom_emoji_id', None)
        if icon_id:
            return str(icon_id)
    return ""

async def send_login_code(data):
    api_id = int(data['apiId'])
    api_hash = data['apiHash'].strip()
    phone = data['phone'].strip()
    if not phone.startswith('+'):
        phone = '+' + phone

    client = TelegramClient(StringSession(), api_id, api_hash)
    await client.connect()
    sent = await client.send_code_request(phone)
    temp_session = client.session.save()
    code_type = type(sent.type).__name__
    await client.disconnect()
    print(json.dumps({
        "success": True,
        "phoneCodeHash": sent.phone_code_hash,
        "tempSession": temp_session,
        "codeType": code_type
    }))

async def qr_login_flow(data):
    api_id = int(data['apiId'])
    api_hash = data['apiHash'].strip()
    password = data.get('password', '').strip()

    client = TelegramClient(StringSession(), api_id, api_hash)
    await client.connect()
    try:
        qr = await client.qr_login()
        print(json.dumps({"status": "qr", "url": qr.url}), flush=True)
        try:
            await qr.wait(timeout=120)
        except SessionPasswordNeededError:
            if not password:
                print(json.dumps({
                    "status": "error",
                    "message": "حسابك محمي بكلمة مرور التحقق بخطوتين (2FA). اكتب كلمة مرور 2FA في الخانة ثم اضغط زر QR مرة أخرى."
                }), flush=True)
                return
            await client.sign_in(password=password)

        final_session = client.session.save()
        print(json.dumps({"status": "success", "sessionString": final_session}), flush=True)
    except asyncio.TimeoutError:
        print(json.dumps({"status": "error", "message": "انتهت مهلة مسح رمز QR (دقيقتان). اضغط الزر للمحاولة مجدداً."}), flush=True)
    finally:
        await client.disconnect()


async def verify_login_code(data):
    api_id = int(data['apiId'])
    api_hash = data['apiHash'].strip()
    phone = data['phone'].strip()
    code = data['code'].strip()
    password = data.get('password', '').strip()
    phone_code_hash = data['phoneCodeHash'].strip()
    temp_session = data['tempSession'].strip()

    client = TelegramClient(StringSession(temp_session), api_id, api_hash)
    await client.connect()
    try:
        await client.sign_in(phone=phone, code=code, phone_code_hash=phone_code_hash)
    except SessionPasswordNeededError:
        if not password:
            await client.disconnect()
            print(json.dumps({
                "success": False,
                "needPassword": True,
                "message": "حساب التلغرام محمي بكلمة مرور التحقق بخطوتين (2FA)، يرجى إدخالها."
            }))
            return
        await client.sign_in(password=password)

    final_session = client.session.save()
    await client.disconnect()
    print(json.dumps({
        "success": True,
        "sessionString": final_session
    }))

async def inspect_bot_buttons(client, bot_username, product_btn_name, offer_keyword):
    bot_username = bot_username.strip().lstrip('@')
    if not bot_username:
        return None

    try:
        entity = await client.get_entity(bot_username)
        await client.send_message(entity, '/shop')
        await asyncio.sleep(2.8)

        messages = await client.get_messages(entity, limit=3)
        shop_msg = None
        for m in messages:
            if m.buttons:
                shop_msg = m
                break

        if not shop_msg:
            await client.send_message(entity, '/start')
            await asyncio.sleep(2.5)
            messages = await client.get_messages(entity, limit=3)
            for m in messages:
                if m.buttons:
                    shop_msg = m
                    break

        if not shop_msg or not shop_msg.buttons:
            return {"bot": bot_username, "found": False, "reason": "لم يتم العثور على أزرار في البوت"}

        target_prod_key = (product_btn_name or '').strip().lower()
        clicked_prod = False
        extracted_emoji_id = ""
        prod_unavailable_on_main = False

        if target_prod_key:
            for row in shop_msg.buttons:
                for btn in row:
                    btext = (btn.text or '').strip()
                    if target_prod_key in btext.lower():
                        extracted_emoji_id = get_button_emoji_id(btn)
                        prod_unavailable_on_main = is_button_unavailable(btn)
                        try:
                            await btn.click()
                            clicked_prod = True
                            await asyncio.sleep(2.8)
                        except Exception:
                            pass
                        break
                if clicked_prod:
                    break

        # Re-fetch latest message after clicking product button
        messages_after = await client.get_messages(entity, limit=3)
        offers_msg = None
        for m in messages_after:
            if m.buttons:
                offers_msg = m
                break

        if not offers_msg or not offers_msg.buttons:
            return {"bot": bot_username, "found": False, "reason": "لا توجد قائمة باقات"}

        target_offer_key = (offer_keyword or '').strip().lower()
        for row in offers_msg.buttons:
            for btn in row:
                btext = (btn.text or '').strip()
                if not btext:
                    continue
                if not target_offer_key or target_offer_key in btext.lower():
                    price = extract_price(btext)
                    if price is not None:
                        unavail = prod_unavailable_on_main or is_button_unavailable(btn)
                        btn_emoji = get_button_emoji_id(btn) or extracted_emoji_id
                        return {
                            "bot": bot_username,
                            "found": True,
                            "price": price,
                            "available": not unavail,
                            "buttonText": btext,
                            "customEmojiId": btn_emoji
                        }

        return {"bot": bot_username, "found": False, "reason": "لم يتم العثور على الباقة المطابقة"}
    except Exception as e:
        return {"bot": bot_username, "found": False, "reason": str(e)}

async def run_sync(data):
    api_id = int(data['apiId'])
    api_hash = data['apiHash'].strip()
    session_str = data['sessionString'].strip()
    offers = data.get('offers', [])

    client = TelegramClient(StringSession(session_str), api_id, api_hash)
    await client.connect()
    if not await client.is_user_authorized():
        await client.disconnect()
        print(json.dumps({"success": False, "message": "جلسة حساب المراقب غير متصلة، يرجى تسجيل الدخول مرة أخرى."}))
        return

    results = []
    for off in offers:
        offer_id = off['offerId']
        bots_raw = off.get('sourceBots', '')
        prod_btn = off.get('sourceProductBtn', '')
        offer_kw = off.get('sourceOfferKeyword', '')

        bots = [b.strip() for b in re.split(r'[,،\s]+', bots_raw) if b.strip()]
        best_result = None
        any_found_unavailable = None
        logs = []

        for b in bots:
            res = await inspect_bot_buttons(client, b, prod_btn, offer_kw)
            if not res:
                continue
            if res.get('found'):
                logs.append(f"@{res['bot']}: ${res['price']} ({'متوفر' if res['available'] else 'غير متوفر'})")
                if res['available']:
                    if best_result is None or res['price'] < best_result['price']:
                        best_result = res
                else:
                    if any_found_unavailable is None or res['price'] < any_found_unavailable['price']:
                        any_found_unavailable = res
            else:
                logs.append(f"@{res.get('bot', b)}: {res.get('reason', 'غير موجود')}")

        chosen = best_result if best_result is not None else any_found_unavailable
        if chosen:
            results.append({
                "offerId": offer_id,
                "found": True,
                "sourcePrice": chosen['price'],
                "available": chosen['available'],
                "customEmojiId": chosen.get('customEmojiId', ''),
                "log": " | ".join(logs)
            })
        else:
            results.append({
                "offerId": offer_id,
                "found": False,
                "log": " | ".join(logs) if logs else "لم يتم العثور على نتائج"
            })

    await client.disconnect()
    print(json.dumps({"success": True, "results": results}))

async def main():
    raw_in = sys.stdin.read()
    data = json.loads(raw_in)
    action = data.get('action')
    try:
        if action == 'send_code':
            await send_login_code(data)
        elif action == 'verify_code':
            await verify_login_code(data)
        elif action == 'qr_login':
            await qr_login_flow(data)
        elif action == 'sync':
            await run_sync(data)
        else:
            print(json.dumps({"success": False, "message": "Unknown action"}))
    except Exception as e:
        print(json.dumps({"success": False, "message": str(e)}))

if __name__ == '__main__':
    asyncio.run(main())
