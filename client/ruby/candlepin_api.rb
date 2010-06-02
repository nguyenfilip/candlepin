require 'base64'
require 'openssl'
require 'rubygems'
require 'rest_client'
require 'json'
require 'uri'

class Candlepin

  attr_accessor :consumer
  attr_reader :identity_certificate
  attr_accessor :uuid

  # Initialize a connection to candlepin. Can use username/password for 
  # basic authentication, or provide an identity certificate and key to
  # connect as a "consumer".
  def initialize(username=nil, password=nil, cert=nil, key=nil, 
                 host='localhost', port=8443)

    if not username.nil? and not cert.nil?
      raise "Cannot connect with both username and identity cert"
    end

    if username.nil? and cert.nil?
      raise "Need username/password or cert/key"
    end

    @base_url = "https://#{host}:#{port}/candlepin"

    if not cert.nil?
      @identity_certificate = OpenSSL::X509::Certificate.new(cert)
      @identity_key = OpenSSL::PKey::RSA.new(key)
      @uuid = @identity_certificate.subject.to_s.scan(/\/UID=([^\/=]+)/)[0][0]
      create_ssl_client()
    else
      create_basic_client(username, password)
    end

  end

  def upload_satellite_certificate(certificate)
    post('/certificates', Base64.encode64(certificate).gsub(/\n/, ""))
  end

  def register(name, type=:system, uuid=nil)
    consumer = {
      :type => {:label => type},
      :name => name
    }

    consumer[:uuid] = uuid if not uuid.nil?

    @consumer = post('/consumers', consumer)
    return @consumer
  end

  def get_owners
    get('/owners')
  end

  def get_owner(owner_id)
    get("/owners/#{owner_id}")
  end

  def create_owner(owner_name)
    owner = {
      'key' => owner_name,
      'displayName' => owner_name
    }

    post('/owners', owner)
  end

  def delete_owner(owner_id)
    delete("/owners/#{owner_id}")
  end

  def create_user(owner_id, login, password)
    user = {
      'login' => login,
      'password' => password
    }

    post("/owners/#{owner_id}/users", user)
  end

  def get_consumer_types
    get('/consumertypes')
  end

  def get_consumer_type(type_id)
    get("/consumertypes/#{type_id}")
  end

  def create_consumer_type(type_label)
    consumer_type =  {
      'label' => type_label
    }

    post('/consumertypes', consumer_type)
  end

  def delete_consumer_type(type_id)
    delete("/consumertypes/#{type_id}")
  end
  
  def get_pool(poolid)
    get("/pools/#{poolid}'")
  end

  def get_pools(params = {})
    path = "/pools?"
    path << "consumer=#{params[:consumer]}&" if params[:consumer]
    path << "owner=#{params[:owner]}&" if params[:owner]
    path << "product=#{params[:product]}&" if params[:product]
    path << "listall=#{params[:listall]}&" if params[:listall]
    return get(path)
  end
  
  def create_pool(product_id, owner_id,  subscription_id, 
	          start_date=nil, end_date=nil, quantity = 100)
    start_date ||= Date.today
    end_date ||= Date.today + 365

    pool = {
      'activeSubscription' => false,
      'subscriptionId' => subscription_id,
      'quantity' => quantity,
      'consumed' => 0,
      'startDate' => start_date,
      'endDate' => end_date,
      'productId' => product_id,
      'owner' => { 
        'id' => owner_id
      }          
    }
    
    post('/pools', pool)
  end

  def refresh_pools(owner_key)
    put("/owners/#{owner_key}/subscriptions")
  end

  # TODO: Add support for serial filtering:
  def get_certificates()
    path = "/consumers/#{@uuid}/certificates"
    return get(path)
  end

  def get_entitlement(entitlement_id)
    get("/entitlements/#{entitlement_id}")
  end

  def unregister(uuid = nil)
    uuid = @uuid unless uuid
    delete("/consumers/#{uuid}")
  end

  def revoke_all_entitlements()
    delete("/consumers/#{@uuid}/entitlements")
  end

  def list_products
    get("/products")
  end
  
  def create_content(name, hash, label, type, vendor,
                     contentUrl, gpgUrl)
    content = {
      'name' => name,
      'hash' => hash,
      'label' => label,
      'type' => type,
      'vendor' => vendor,
      'contentUrl' => contentUrl,
      'gpgUrl' => gpgUrl
    }
    post("/content", content)
  end

  def list_content
    get("/content")
  end

  def get_content(content_id)
    get("/content/id/#{content_id}")
  end

    def add_content_to_product(product_uuid, content_label, enabled=true) 
      post("/products/#{product_uuid}/content/#{content_label}?enabled=#{enabled}")
    end
    
  def create_product(label, name, hash, version = 1, variant = 'ALL', 
                     arch='ALL', type='SVC',childProducts=[], attributes = {})

    content_name = label + "_content"
    product = {
      'name' => name,
      'label' => label,
      'hash' => hash,
      'arch' => arch,
      'id' => label,
      'version' => version,
      'variant' => variant,
      'type' => type,
      'attributes' => attributes.collect {|k,v| {'name' => k, 'value' => v}}
    }

    if not childProducts.empty?
      childIds = '?childId=' + childProducts.join('&childId=')
    else
      childIds = ''
    end

    post("/products#{childIds}", product)
  end

  def get_product(product_id)
    get("/products/#{product_id}")
  end

  def get_product_cert(product_id)
    get("/products/#{product_id}/certificate")
  end
  
  # TODO: Should we change these to bind to better match terminology?
  def consume_pool(pool)
    post("/consumers/#{@uuid}/entitlements?pool=#{pool}")
  end

  def consume_product(product, quantity = nil)
    path = "/consumers/#{@uuid}/entitlements?product=#{product}"
    path << "&quantity=#{quantity}" if quantity
    post(path)
  end

  def consume_token(token)
    post("/consumers/#{@uuid}/entitlements?token=#{token}")
  end

  def list_entitlements(product_id = nil)
    path = "/consumers/#{@uuid}/entitlements"
    path << "?product=#{product_id}" if product_id
    get(path)
  end

  def list_rules()
    get_text("/rules")
  end

  def upload_rules(rule_set)
    post_text("/rules/", rule_set)
  end

  def get_consumer(cid)
    get("/consumers/#{cid}")
  end

  def unbind_entitlement(eid)
    delete("/consumers/#{@uuid}/entitlements/#{eid}")
  end

  def get_subscriptions(owner_id)
    return get("/owners/#{owner_id}/subscriptions")
  end

  def create_subscription(owner_id, data)
    return post("/owners/#{owner_id}/subscriptions", data)
  end

  def delete_subscription(subscription)
    return delete("/subscriptions/#{subscription}")
  end

  def get_subscription_tokens
    return get("/subscriptiontokens")
  end

  def create_subscription_token(data)
    return post("/subscriptiontokens", data)
  end

  def delete_subscription_token(subscription)
    return delete("/subscriptiontokens/#{subscription}")
  end

  def get_certificates(serials = [])
    path = "/consumers/#{@uuid}/certificates"
    path += "?serials=" + serials.join(",") if serials.length > 0
    return get(path)
  end

  def get_status
    return get("/status/")
  end

  def get_certificate_serials
    return get("/consumers/#{@uuid}/certificates/serials")
  end

  private

  def create_basic_client(username=nil, password=nil)
    @client = RestClient::Resource.new(@base_url, 
                                       username, password)
  end

  def create_ssl_client
    @client = RestClient::Resource.new(@base_url,
                                       :ssl_client_cert => @identity_certificate, 
                                       :ssl_client_key => @identity_key)
  end

  def get(uri)
    response = @client[URI.escape(uri)].get :accept => :json

    return JSON.parse(response.body)
  end

  def get_text(uri)
    response = @client[URI.escape(uri)].get
    return (response.body)
  end

  def post(uri, data=nil)
    data = data.to_json if not data.nil?
    response = @client[URI.escape(uri)].post(data, :content_type => :json, :accept => :json)

    return JSON.parse(response.body)
  end
  
  def post_text(uri, data=nil)
    response = @client[URI.escape(uri)].post(data, :content_type => 'text/plain', :accept => 'text/plain' )
    return response.body
  end

  def put(uri, data=nil)
    data = data.to_json if not data.nil?
    response = @client[uri].put(data, :content_type => :json, :accept => :json)

    return JSON.parse(response.body) unless response.body.empty?
  end

  def delete(uri)
    @client[URI.escape(uri)].delete
  end
end

